package br.ars.user_service.cdn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class BunnyCdnClient {

    private static final Logger log = LoggerFactory.getLogger(BunnyCdnClient.class);

    @Value("${bunny.storage.base-url}")             // ex.: https://storage.bunnycdn.com
    private String baseUrl;

    @Value("${bunny.storage.zone-name}")            // ex.: sua_storage_zone
    private String zoneName;

    @Value("${bunny.storage.access-key}")           // header AccessKey
    private String accessKey;

    @Value("${bunny.storage.folder-prefix:users}")  // ex.: users (usado no método compat)
    private String folderPrefix;

    @Value("${bunny.cdn.base-url}")                 // ex.: https://ars-vnh.b-cdn.net
    private String publicCdnBase;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Sobe o arquivo usando a key já montada (ex.: "users/max<uuid>.jpg").
     * NÃO retorna URL; apenas executa o PUT no Storage.
     */
    public void uploadAvatar(MultipartFile file, String key) {
        if (file == null) {
            log.warn("[Bunny] uploadAvatar: MultipartFile é null para key={}", key);
            throw new IllegalArgumentException("Arquivo de avatar nulo.");
        }
        if (file.isEmpty()) {
            log.warn("[Bunny] uploadAvatar: MultipartFile vazio para key={}, originalFilename={}", key, file.getOriginalFilename());
            throw new IllegalArgumentException("Arquivo de avatar vazio.");
        }

        try {
            long reportedSize = file.getSize();
            String originalName = file.getOriginalFilename();
            String contentType = (file.getContentType() != null && !file.getContentType().isBlank())
                    ? file.getContentType()
                    : "application/octet-stream";

            String path = trimLeftRight(key, "/"); // evita // duplicado
            String storageUrl = String.format("%s/%s/%s", trimRight(baseUrl), zoneName, path);

            log.info("[Bunny] Preparando upload | key={} | storageUrl={} | originalFilename={} | reportedSize={} | contentType={}",
                    path, storageUrl, originalName, reportedSize, contentType);

            byte[] bytes = StreamUtils.copyToByteArray(file.getInputStream());
            log.debug("[Bunny] Bytes lidos do MultipartFile | length={}", bytes.length);

            HttpHeaders headers = new HttpHeaders();
            headers.set("AccessKey", accessKey);
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.setContentLength(bytes.length);

            HttpEntity<byte[]> entity = new HttpEntity<>(bytes, headers);
            ResponseEntity<String> resp = restTemplate.exchange(storageUrl, HttpMethod.PUT, entity, String.class);

            log.info("[Bunny] Resposta do Storage | status={} | bodyPreview={}",
                    resp.getStatusCode(), safePreview(resp.getBody()));

            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.error("[Bunny] Falha no upload | status={} | url={}", resp.getStatusCode(), storageUrl);
                throw new RuntimeException("Falha upload Bunny: " + resp.getStatusCode());
            }

            log.info("[Bunny] Upload concluído com sucesso | key={} | bytes={}", path, bytes.length);

        } catch (IOException e) {
            log.error("[Bunny] Erro lendo/enviando arquivo | key={} | msg={}", key, e.getMessage(), e);
            throw new RuntimeException("Erro lendo/enviando arquivo: " + e.getMessage(), e);
        }
    }

    /**
     * Assinatura de compatibilidade:
     * Constrói automaticamente "users/<primeiroNome><uuid>.<ext>", faz o upload
     * e retorna a URL pública final.
     */
    public String uploadAvatar(MultipartFile file, String userUuid, String desiredBaseName) {
        if (file == null || file.isEmpty()) {
            log.warn("[Bunny] uploadAvatar(compat): arquivo vazio/nulo | userUuid={} | desiredBaseName={}", userUuid, desiredBaseName);
            return null;
        }

        // base "max" a partir do nome desejado
        String baseFromName = buildBaseFromFullName(desiredBaseName);
        String safeBase = sanitizeBaseName(baseFromName);
        if (safeBase == null || safeBase.isBlank()) safeBase = "user";

        // tenta detectar extensão
        String ext = guessExt(file.getOriginalFilename(), file.getContentType());
        String fileName = (ext != null && !ext.isBlank())
                ? (safeBase + userUuid + "." + ext)
                : (safeBase + userUuid);

        String folder = trimLeftRight(folderPrefix, "/");
        String key = (folder.isBlank() ? "" : folder + "/") + fileName;

        log.info("[Bunny] Compat: montando key automática | desiredBaseName={} | safeBase={} | uuid={} | ext={} | key={}",
                desiredBaseName, safeBase, userUuid, ext, key);

        // faz upload usando a key já montada
        uploadAvatar(file, key);

        // monta URL pública e retorna
        String safe = URLEncoder.encode(key, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
        String publicUrl = trimRight(publicCdnBase) + "/" + safe;

        log.info("[Bunny] Compat: URL pública gerada | publicUrl={}", publicUrl);
        return publicUrl;
    }

    // ===== Helpers =====

    private String trimRight(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    private String trimLeftRight(String s, String ch) {
        if (s == null || s.isEmpty()) return s;
        String out = s;
        while (out.startsWith(ch)) out = out.substring(1);
        while (out.endsWith(ch)) out = out.substring(0, out.length() - 1);
        return out;
    }

    // "Max da Silva" -> "max"
    private String buildBaseFromFullName(String fullName) {
        if (fullName == null) return null;
        String[] parts = fullName.trim().split("\\s+");
        return parts.length > 0 ? parts[0] : fullName.trim();
    }

    private String sanitizeBaseName(String s) {
        if (s == null) return null;
        String noAccents = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String out = noAccents.replaceAll("[^a-zA-Z0-9-_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "")
                .toLowerCase();
        return out.isBlank() ? null : out;
    }

    private String guessExt(String original, String contentType) {
        if (contentType != null) {
            switch (contentType.toLowerCase()) {
                case "image/png":  return "png";
                case "image/jpeg":
                case "image/jpg":  return "jpg";
                case "image/webp": return "webp";
                case "image/gif":  return "gif";
                case "image/bmp":  return "bmp";
                case "image/svg+xml": return "svg";
                case "image/heic": return "heic";
                case "image/heif": return "heif";
            }
        }
        if (original != null && original.contains(".")) {
            String ext = original.substring(original.lastIndexOf('.') + 1).toLowerCase();
            if (ext.matches("[a-z0-9]{1,6}")) return ext;
        }
        log.debug("[Bunny] guessExt: contentType/original vazios ou desconhecidos, usando 'jpg'");
        return "jpg";
    }

    private String safePreview(String body) {
        if (body == null) return "<null>";
        String trimmed = body.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
    }
}

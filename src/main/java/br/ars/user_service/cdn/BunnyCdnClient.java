package br.ars.user_service.cdn;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class BunnyCdnClient {

    @Value("${bunny.storage.base-url}")             // ex.: https://storage.bunnycdn.com
    private String baseUrl;

    @Value("${bunny.storage.zone-name}")            // ex.: sua_storage_zone
    private String zoneName;

    @Value("${bunny.storage.access-key}")           // header AccessKey
    private String accessKey;

    @Value("${bunny.storage.folder-prefix:users}")  // ex.: users
    private String folderPrefix;

    @Value("${bunny.cdn.base-url}")                 // ex.: https://ars-vnh.b-cdn.net
    private String publicCdnBase;

    private final RestTemplate restTemplate = new RestTemplate();

    /** Upload com MultipartFile e key já montada (ex.: "users/max<uuid>.jpg"). */
    public void uploadAvatar(MultipartFile file, String key) {
        if (file == null || file.isEmpty()) {
            log.warn("[Bunny] uploadAvatar: arquivo nulo/vazio | key={}", key);
            throw new IllegalArgumentException("Arquivo de avatar vazio.");
        }
        try {
            byte[] bytes = StreamUtils.copyToByteArray(file.getInputStream());
            String ct = (file.getContentType() != null && !file.getContentType().isBlank())
                    ? file.getContentType()
                    : "application/octet-stream";
            uploadBytes(bytes, ct, key);
        } catch (IOException e) {
            log.error("[Bunny] erro lendo MultipartFile: {}", e.getMessage(), e);
            throw new RuntimeException("Erro lendo arquivo para upload", e);
        }
    }

    /** Upload direto de bytes (para uso no worker/fila). */
    public void uploadBytes(byte[] bytes, String contentType, String key) {
        if (bytes == null || bytes.length == 0) {
            log.warn("[Bunny] uploadBytes: bytes vazios | key={}", key);
            throw new IllegalArgumentException("bytes vazios");
        }
        String path = trimLeftRight(key, "/");
        String storageUrl = String.format("%s/%s/%s", trimRight(baseUrl), zoneName, path);
        String ct = (contentType != null && !contentType.isBlank()) ? contentType : "application/octet-stream";

        log.info("[Bunny] PUT bytes | key={} | url={} | ct={} | len={}", path, storageUrl, ct, bytes.length);

        HttpHeaders headers = new HttpHeaders();
        headers.set("AccessKey", accessKey);
        headers.setContentType(MediaType.parseMediaType(ct));
        headers.setContentLength(bytes.length);

        HttpEntity<byte[]> entity = new HttpEntity<>(bytes, headers);
        ResponseEntity<String> resp = restTemplate.exchange(storageUrl, HttpMethod.PUT, entity, String.class);

        log.info("[Bunny] Resposta | status={} | bodyPreview={}", resp.getStatusCode(), safePreview(resp.getBody()));
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Falha upload Bunny: " + resp.getStatusCode());
        }
    }

    /** Compat: monta "users/<firstName><uuid>.<ext>" e retorna URL pública. */
    public String uploadAvatar(MultipartFile file, String userUuid, String desiredBaseName) {
        if (file == null || file.isEmpty()) {
            log.warn("[Bunny] compat uploadAvatar: arquivo nulo/vazio");
            return null;
        }
        String safeBase = sanitizeBaseName(buildBaseFromFullName(desiredBaseName));
        if (safeBase == null || safeBase.isBlank()) safeBase = "user";

        String ext = guessExt(file.getOriginalFilename(), file.getContentType());
        String fileName = safeBase + userUuid + "." + ext;

        String folder = trimLeftRight(folderPrefix, "/");
        String key = (folder.isBlank() ? "" : folder + "/") + fileName;

        uploadAvatar(file, key);

        String safe = URLEncoder.encode(key, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("%2F", "/");
        String url = trimRight(publicCdnBase) + "/" + safe;
        log.info("[Bunny] compat URL pública = {}", url);
        return url;
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
        return "jpg";
    }

    private String safePreview(String body) {
        if (body == null) return "<null>";
        String trimmed = body.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
    }
}

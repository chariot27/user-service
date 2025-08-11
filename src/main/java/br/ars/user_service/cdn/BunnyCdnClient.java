package br.ars.user_service.cdn;

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

    @Value("${bunny.storage.base-url}")
    private String baseUrl;              // ex.: https://storage.bunnycdn.com
    @Value("${bunny.storage.zone-name}")
    private String zoneName;             // ex.: sua_storage_zone
    @Value("${bunny.storage.access-key}")
    private String accessKey;            // header AccessKey
    @Value("${bunny.storage.folder-prefix:users}")
    private String folderPrefix;         // ex.: users
    @Value("${bunny.cdn.base-url}")
    private String publicCdnBase;        // ex.: https://ars-vnh.b-cdn.net

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Sobe o arquivo do jeito que veio e retorna a URL pública:
     * https://<pullzone>/users/<primeiroNome><uuid>.<ext>
     * (se não achar extensão, sobe sem extensão)
     */
    public String uploadAvatar(MultipartFile file, String userUuid, String desiredBaseName) {
        if (file == null || file.isEmpty()) return null;

        // base pelo primeiro nome: "Max da Silva" -> "max"
        String baseFromName = buildBaseFromFullName(desiredBaseName);
        String safeBase = sanitizeBaseName(baseFromName);
        if (safeBase == null || safeBase.isBlank()) safeBase = "user";

        // tenta pegar extensão real
        String ext = guessExt(file.getOriginalFilename(), file.getContentType());

        // monta nome final
        String fileName = ext != null && !ext.isBlank()
                ? (safeBase + userUuid + "." + ext)
                : (safeBase + userUuid);

        String path = trimLeftRight(folderPrefix, "/") + "/"; // "users/"
        String storageUrl = String.format("%s/%s/%s%s",
                trimRight(baseUrl), zoneName, path, fileName);

        try {
            byte[] bytes = StreamUtils.copyToByteArray(file.getInputStream());

            // content-type: usa o vindo do arquivo; senão, tenta por extensão; senão octet-stream
            String contentType = file.getContentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = mimeFromExt(ext);
            }
            if (contentType == null) contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;

            HttpHeaders headers = new HttpHeaders();
            headers.set("AccessKey", accessKey);
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.setContentLength(bytes.length);

            HttpEntity<byte[]> entity = new HttpEntity<>(bytes, headers);
            ResponseEntity<String> resp = restTemplate.exchange(storageUrl, HttpMethod.PUT, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Falha upload Bunny: " + resp.getStatusCode());
            }

            // URL pública final
            String safe = URLEncoder.encode(path + fileName, StandardCharsets.UTF_8)
                    .replace("+", "%20")
                    .replace("%2F", "/");
            return trimRight(publicCdnBase) + "/" + safe;

        } catch (IOException e) {
            throw new RuntimeException("Erro lendo/enviando arquivo: " + e.getMessage(), e);
        }
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

    /** Extrai extensão a partir do nome e/ou content-type */
    private String guessExt(String original, String contentType) {
        if (original != null && original.contains(".")) {
            String ext = original.substring(original.lastIndexOf('.') + 1).toLowerCase();
            if (ext.matches("[a-z0-9]{1,6}")) return ext;
        }
        if (contentType != null) {
            switch (contentType.toLowerCase()) {
                case "image/png": return "png";
                case "image/jpeg": return "jpg";
                case "image/jpg": return "jpg";
                case "image/webp": return "webp";
                case "image/gif": return "gif";
                case "image/bmp": return "bmp";
                case "image/svg+xml": return "svg";
                case "image/heic": return "heic";
                case "image/heif": return "heif";
            }
        }
        return null; // sem extensão
    }

    /** Deriva mime-type pela extensão quando content-type não vier preenchido */
    private String mimeFromExt(String ext) {
        if (ext == null) return null;
        switch (ext.toLowerCase()) {
            case "png":  return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif":  return "image/gif";
            case "bmp":  return "image/bmp";
            case "svg":  return "image/svg+xml";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            default:     return null;
        }
    }
}

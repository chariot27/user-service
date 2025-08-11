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
    private String baseUrl;

    @Value("${bunny.storage.zone-name}")
    private String zoneName;

    @Value("${bunny.storage.access-key}")
    private String accessKey;

    @Value("${bunny.storage.folder-prefix:users}")
    private String folderPrefix;

    @Value("${bunny.cdn.base-url}")
    private String publicCdnBase;

    private final RestTemplate restTemplate = new RestTemplate();

    /** Sobe o avatar como: users/{uuid}/{baseName}.{ext} e retorna a URL pública completa */
    public String uploadAvatar(MultipartFile file, String userUuid, String desiredBaseName) {
        if (file == null || file.isEmpty()) return null;

        String ext = guessExt(file.getOriginalFilename(), file.getContentType());
        String safeBase = sanitizeBaseName(desiredBaseName);
        if (safeBase == null || safeBase.isBlank()) {
            safeBase = userUuid + "_avatar";
        }
        String fileName = ext != null ? (safeBase + "." + ext) : safeBase;

        String path = folderPrefix + "/" + userUuid + "/"; // users/{uuid}/
        String storageUrl = String.format("%s/%s/%s%s", trimRight(baseUrl), zoneName, path, fileName);

        try {
            byte[] bytes = StreamUtils.copyToByteArray(file.getInputStream());

            HttpHeaders headers = new HttpHeaders();
            headers.set("AccessKey", accessKey);
            headers.setContentType(MediaType.parseMediaType(
                file.getContentType() != null ? file.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE));
            headers.setContentLength(bytes.length);

            HttpEntity<byte[]> entity = new HttpEntity<>(bytes, headers);
            ResponseEntity<String> resp = restTemplate.exchange(storageUrl, HttpMethod.PUT, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Falha upload Bunny: " + resp.getStatusCode());
            }

            // URL pública
            String safe = URLEncoder.encode(path + fileName, StandardCharsets.UTF_8)
                    .replace("+","%20").replace("%2F","/");
            return (trimRight(publicCdnBase) + "/" + safe);

        } catch (IOException e) {
            throw new RuntimeException("Erro lendo imagem: " + e.getMessage(), e);
        }
    }

    private String trimRight(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
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
        if (original != null && original.contains(".")) {
            String ext = original.substring(original.lastIndexOf('.') + 1).toLowerCase();
            if (ext.matches("[a-z0-9]{1,5}")) return ext;
        }
        if (contentType == null) return null;
        switch (contentType) {
            case "image/png": return "png";
            case "image/jpeg": return "jpg";
            case "image/webp": return "webp";
            case "image/gif": return "gif";
            default: return null;
        }
    }
}

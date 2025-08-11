package br.ars.user_service.cdn;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
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
    private String accessKey;            // atenção: propriedade é access-key
    @Value("${bunny.storage.folder-prefix:users}")
    private String folderPrefix;         // ex.: users
    @Value("${bunny.cdn.base-url}")
    private String publicCdnBase;        // ex.: https://ars-vnh.b-cdn.net

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Converte a imagem para WEBP e envia ao Bunny Storage.
     * Retorna a URL pública no formato:
     * https://<pullzone>/users/<primeiroNome><uuid>.webp
     *
     * Mantém o nome do método.
     */
    public String uploadAvatar(MultipartFile file, String userUuid, String desiredBaseName) {
        if (file == null || file.isEmpty()) return null;

        // Extensão sempre webp após conversão
        String ext = "webp";

        // primeira palavra do nome -> "max" a partir de "Max da Silva"
        String baseFromName = buildBaseFromFullName(desiredBaseName);
        String safeBase = sanitizeBaseName(baseFromName);
        if (safeBase == null || safeBase.isBlank()) {
            safeBase = "user";
        }

        // users/<nome><uuid>.webp
        String fileName = safeBase + userUuid + "." + ext;
        String path = trimLeftRight(folderPrefix, "/") + "/"; // "users/"

        // URL de upload (Storage Zone)
        String storageUrl = String.format("%s/%s/%s%s",
                trimRight(baseUrl), zoneName, path, fileName);

        try {
            // 1) Converte qualquer formato recebido para WEBP
            byte[] webpBytes = convertToWebp(file);

            // 2) Envia para o Bunny Storage
            HttpHeaders headers = new HttpHeaders();
            headers.set("AccessKey", accessKey);
            headers.setContentType(MediaType.parseMediaType("image/webp"));
            headers.setContentLength(webpBytes.length);

            HttpEntity<byte[]> entity = new HttpEntity<>(webpBytes, headers);
            ResponseEntity<String> resp = restTemplate.exchange(storageUrl, HttpMethod.PUT, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Falha upload Bunny: " + resp.getStatusCode());
            }

            // 3) Retorna URL pública final
            String safe = URLEncoder.encode(path + fileName, StandardCharsets.UTF_8)
                                     .replace("+", "%20")
                                     .replace("%2F", "/");
            return trimRight(publicCdnBase) + "/" + safe;

        } catch (IOException e) {
            throw new RuntimeException("Erro processando imagem: " + e.getMessage(), e);
        }
    }

    // ---- Helpers ----

    // Converte a imagem recebida para WEBP usando Thumbnailator.
    // Ajuste opcional: .outputQuality(0.8) para comprimir; .size(512, 512) para redimensionar.
    private byte[] convertToWebp(MultipartFile file) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Thumbnails.of(file.getInputStream())
                  .scale(1.0)             // mantém dimensão original
                  .outputFormat("webp")   // força WEBP
                  // .outputQuality(0.8)  // (opcional) qualidade/compactação
                  .toOutputStream(baos);
        return baos.toByteArray();
    }

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
}

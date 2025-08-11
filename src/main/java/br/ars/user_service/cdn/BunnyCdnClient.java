package br.ars.user_service.cdn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.nio.ImageWriter;
import com.sksamuel.scrimage.webp.WebpWriter;

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
     * Converte a imagem para WEBP e envia ao Bunny Storage.
     * Retorna a URL pública no formato:
     * https://<pullzone>/users/<primeiroNome><uuid>.webp
     */
    public String uploadAvatar(MultipartFile file, String userUuid, String desiredBaseName) {
        if (file == null || file.isEmpty()) return null;

        // primeira palavra do nome -> "max" a partir de "Max da Silva"
        String baseFromName = buildBaseFromFullName(desiredBaseName);
        String safeBase = sanitizeBaseName(baseFromName);
        if (safeBase == null || safeBase.isBlank()) {
            safeBase = "user";
        }

        // users/<nome><uuid>.webp
        String fileName = safeBase + userUuid + ".webp";
        String path = trimLeftRight(folderPrefix, "/") + "/"; // "users/"

        // URL de upload (Storage Zone)
        String storageUrl = String.format("%s/%s/%s%s",
                trimRight(baseUrl), zoneName, path, fileName);

        try {
            // 1) Converte qualquer formato recebido para WEBP (writer padrão)
            byte[] webpBytes = toWebp(file, 0); // maxSize=0 => sem redimensionar

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

    // ---- Helpers de imagem (Scrimage) ----

    /**
     * Converte para WEBP usando o writer padrão da lib.
     * @param maxSize se >0, redimensiona para no máx. maxSize x maxSize (preserva proporção)
     */
    private byte[] toWebp(MultipartFile file, int maxSize) throws IOException {
        ImmutableImage image = ImmutableImage.loader().fromStream(file.getInputStream());
        if (maxSize > 0) {
            image = image.max(maxSize, maxSize); // limita dimensões mantendo aspect ratio
        }
        ImageWriter writer = WebpWriter.DEFAULT;   // sem ajuste de quality nessa versão
        return image.bytes(writer);                // retorna bytes em WEBP
    }

    // ---- Helpers gerais ----

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

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

    /** Envia avatar: users/{uuid}/{uuid}_avatar.ext -> retorna URL pública */
    public String uploadAvatar(MultipartFile file, String userUuid) {
        if (file == null || file.isEmpty()) return null;

        String ext = guessExt(file.getOriginalFilename(), file.getContentType());
        String fileName = userUuid + "_avatar" + (ext != null ? "." + ext : "");
        return uploadInternal(file, folderPrefix + "/" + userUuid + "/", fileName);
    }

    /** Upload com nome final definido por você (ex.: "perfil_do_joao.webp") */
    public String uploadWithName(MultipartFile file, String subfolder, String finalFileName) {
        if (file == null || file.isEmpty()) return null;
        String base = folderPrefix.endsWith("/") ? folderPrefix : folderPrefix + "/";
        String path = base + (subfolder != null && !subfolder.isBlank() ? subfolder + "/" : "");
        return uploadInternal(file, path, finalFileName);
    }

    /** Core */
    private String uploadInternal(MultipartFile file, String path, String finalFileName) {
        String storageUrl = String.format("%s/%s/%s%s", baseUrl, zoneName, path, finalFileName);

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

            String safe = URLEncoder.encode(path + finalFileName, StandardCharsets.UTF_8)
                    .replace("+", "%20").replace("%2F", "/");
            return (publicCdnBase.endsWith("/") ? publicCdnBase : publicCdnBase + "/") + safe;

        } catch (IOException e) {
            throw new RuntimeException("Erro lendo imagem: " + e.getMessage(), e);
        }
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

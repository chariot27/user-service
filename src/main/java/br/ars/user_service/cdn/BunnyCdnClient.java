package br.ars.user_service.cdn;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class BunnyCdnClient {

    // Pode vir de application.properties (bunny.storage.*) ou de env (CDN_*).
    @Value("${bunny.storage.base-url:${CDN_URI:}}")
    private String baseUrl;                       // ex.: https://br.storage.bunnycdn.com

    @Value("${bunny.storage.zone-name:${CDN_ZONE_NAME:}}")
    private String zoneName;                      // ex.: ars-vnh-bunny

    // aceita access-key tradicional OU api-key OU CDN_ACCESS_KEY
    @Value("${bunny.storage.access-key:${bunny.storage.api-key:${CDN_ACCESS_KEY:}}}")
    private String accessKeyRaw;                  // STORAGE PASSWORD da Storage Zone

    @Value("${bunny.storage.folder-prefix:${CDN_PREFIX:users}}")
    private String folderPrefix;                  // ex.: users

    @Value("${bunny.cdn.base-url:${PUB_URI:}}")
    private String publicCdnBase;                 // ex.: https://ars-vnh.b-cdn.net

    private String accessKey;

    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    void checkConfig() {
        accessKey = accessKeyRaw == null ? null : accessKeyRaw.trim();

        log.info("[Bunny] Boot | baseUrl={} | zoneName={} | folderPrefix={} | cdnBase={} | accessKey(masked)={}",
                baseUrl, zoneName, folderPrefix, publicCdnBase, mask(accessKey));

        if (isBlank(baseUrl))   throw new IllegalStateException("Config faltando: bunny.storage.base-url/CDN_URI");
        if (isBlank(zoneName))  throw new IllegalStateException("Config faltando: bunny.storage.zone-name/CDN_ZONE_NAME");
        if (isBlank(accessKey)) throw new IllegalStateException("Config faltando: bunny.storage.access-key/CDN_ACCESS_KEY (Storage Password da Zone)");
    }

    /** Upload de bytes com key já montada (ex.: "users/max<uuid>.jpg"). */
    public void uploadBytes(byte[] bytes, String contentType, String key) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("bytes vazios");

        final String path = trimSlashes(key);
        final String url  = trimRight(baseUrl) + "/" + zoneName + "/" + path;
        final String ct   = isBlank(contentType) ? "application/octet-stream" : contentType;

        log.info("[Bunny] PUT | url={} | ct={} | len={} | key={} | ak={}",
                url, ct, bytes.length, path, mask(accessKey));

        HttpHeaders h = new HttpHeaders();
        h.set("AccessKey", accessKey);
        h.setContentType(MediaType.parseMediaType(ct));
        h.setContentLength(bytes.length);

        var entity = new HttpEntity<>(bytes, h);
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);

        log.info("[Bunny] Resp | status={} | body={}", resp.getStatusCode(), preview(resp.getBody()));
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Falha upload Bunny: " + resp.getStatusCode());
        }
    }

    /** Upload via MultipartFile (usa uploadBytes por baixo). */
    public void uploadAvatar(org.springframework.web.multipart.MultipartFile file, String key) {
        try {
            if (file == null || file.isEmpty()) throw new IllegalArgumentException("arquivo vazio");
            var bytes = file.getBytes();
            var ct = file.getContentType();
            uploadBytes(bytes, ct, key);
        } catch (java.io.IOException e) {
            log.error("[Bunny] erro lendo arquivo: {}", e.getMessage(), e);
            throw new RuntimeException("Erro lendo arquivo para upload", e);
        }
    }

    // ===== helpers =====
    private static String trimRight(String s){ return s!=null && s.endsWith("/") ? s.substring(0,s.length()-1) : s; }
    private static String trimSlashes(String s){
        if (s==null) return "";
        String out=s;
        while(out.startsWith("/")) out=out.substring(1);
        while(out.endsWith("/")) out=out.substring(0,out.length()-1);
        return out;
    }
    private static boolean isBlank(String s){ return s==null || s.trim().isEmpty(); }
    private static String mask(String s){
        if (isBlank(s)) return "<null>";
        String t=s.trim(); if (t.length()<=6) return "******";
        return t.substring(0,3)+"****"+t.substring(t.length()-3);
    }
    private static String preview(String body){
        if (body==null) return "<null>";
        String t=body.replaceAll("\\s+"," ").trim();
        return t.length()>200 ? t.substring(0,200)+"..." : t;
    }
}

// RateLimitProperties.java
package br.ars.user_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {
    /** Máximo de requisições por janela (por chave) */
    private int limit = 100;
    /** Janela em milissegundos */
    private long windowMillis = Duration.ofSeconds(10).toMillis();
    /** Padrões de caminhos a incluir no rate limit */
    private List<String> include = new ArrayList<>(List.of(
            "/api/users/login",
            "/api/users/register"
    ));
    /** Padrões de caminhos a excluir (actuator, swagger, estáticos, etc.) */
    private List<String> exclude = new ArrayList<>(List.of(
            "/actuator/**",
            "/error",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/webjars/**",
            "/static/**",
            "/favicon.ico"
    ));

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
    public long getWindowMillis() { return windowMillis; }
    public void setWindowMillis(long windowMillis) { this.windowMillis = windowMillis; }
    public List<String> getInclude() { return include; }
    public void setInclude(List<String> include) { this.include = include; }
    public List<String> getExclude() { return exclude; }
    public void setExclude(List<String> exclude) { this.exclude = exclude; }
}

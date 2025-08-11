// WebConfig.java
package br.ars.user_service.config;

import br.ars.user_service.rate.SimpleRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SimpleRateLimitInterceptor limiter;
    private final RateLimitProperties props;

    public WebConfig(SimpleRateLimitInterceptor limiter, RateLimitProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        var reg = registry.addInterceptor(limiter);

        if (!props.getInclude().isEmpty()) {
            reg.addPathPatterns(props.getInclude());
        }
        if (!props.getExclude().isEmpty()) {
            reg.excludePathPatterns(props.getExclude());
        }
    }
}

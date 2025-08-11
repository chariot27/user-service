// SimpleRateLimitInterceptor.java
package br.ars.user_service.rate;

import br.ars.user_service.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@Order(1)
public class SimpleRateLimitInterceptor implements HandlerInterceptor {

    private final VerySimpleRateLimiter limiter;

    public SimpleRateLimitInterceptor(RateLimitProperties props) {
        this.limiter = new VerySimpleRateLimiter(props.getLimit(), props.getWindowMillis());
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        // chave por IP + rota (ajuste se quiser por usuário/authed)
        String key = req.getRemoteAddr() + ":" + req.getRequestURI();
        if (limiter.allow(key)) return true;
        res.setStatus(429); // Too Many Requests
        return false;
    }
}

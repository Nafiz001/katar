package dev.nafiz.katar.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimiter limiter;

    public WebConfig(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(limiter).addPathPatterns("/api/sales/*/queue", "/api/sales/*/holds/**",
                "/api/sales/*/bookings");
    }
}

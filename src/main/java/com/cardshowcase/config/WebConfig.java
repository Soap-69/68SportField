package com.cardshowcase.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.upload-dir}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadDir + "/");
    }

    /**
     * Prevents browsers from caching auth-sensitive pages in the back-forward
     * cache (bfcache). Without this, a user who logged out and then pressed Back
     * could be served a stale /login page whose _csrf hidden field no longer
     * matches the freshly-issued XSRF-TOKEN cookie, resulting in a 403 on submit.
     *
     * Cache-Control: no-store is the correct directive for bfcache prevention
     * (no-cache alone does not block bfcache in all browsers).
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request,
                                     HttpServletResponse response,
                                     Object handler) {
                response.setHeader("Cache-Control", "no-store");
                return true;
            }
        }).addPathPatterns(
            "/login", "/register",
            "/forgot-password", "/reset-password",
            "/account", "/account/**"
        );
    }
}

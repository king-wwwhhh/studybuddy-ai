package org.example.config;

import org.example.auth.AuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 配置跨域和静态资源
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    /** 全局鉴权：/api/** 需登录（白名单除外），/api/admin/** 需管理员 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/login-code", "/api/auth/register",
                        "/api/auth/send-code", "/api/auth/reset-password", "/api/auth/check-email");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    /**
     * 静态资源分流：
     *   /legacy/**      → 旧版单页（用户端主界面，含 canvas 粒子/极光动效）
     *   其余（含 /）    → Vue 应用（登录页 + 管理员后台）
     * 开发时优先读源码目录，便于 npm run build 后刷新即生效。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/legacy/**")
                .addResourceLocations(
                        "file:src/main/resources/static-legacy/",
                        "classpath:/static-legacy/")
                .setCachePeriod(0);
        // 配置静态资源映射
        registry.addResourceHandler("/**")
                .addResourceLocations(
                        "file:src/main/resources/static/",
                        "classpath:/static/")
                .setCachePeriod(0);
    }
}
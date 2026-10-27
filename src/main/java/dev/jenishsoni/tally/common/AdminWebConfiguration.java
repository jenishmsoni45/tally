package dev.jenishsoni.tally.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts every /admin endpoint behind the API key. An interceptor rather than a servlet filter, so
 * the 401 is raised inside Spring MVC and rendered by the one ApiExceptionHandler, and the guard
 * is matched against the same parsed path that selects the handler.
 */
@Configuration(proxyBeanMethods = false)
class AdminWebConfiguration implements WebMvcConfigurer {

    private final AdminApiKeyInterceptor adminApiKey;

    AdminWebConfiguration(AdminApiKeyInterceptor adminApiKey) {
        this.adminApiKey = adminApiKey;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminApiKey).addPathPatterns("/admin/**");
    }

}

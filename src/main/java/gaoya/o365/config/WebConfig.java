package gaoya.o365.config; // 必须与你 SecurityConfig 的 package 一致

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 核心：将 /refer 路径映射到 templates/refer.html
        registry.addViewController("/refer").setViewName("refer");
        
        // 既然你 SecurityConfig 里也放行了 /reg，建议也加上这行
        registry.addViewController("/reg").setViewName("reg");
        
        // 这样访问 http://域名/refer 就会直接显示页面，不再报 404
    }
}
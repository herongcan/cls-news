package com.herc.clsnews.config;

import com.herc.clsnews.web.VisitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 只注册一个拦截器，路径写死成首页这两条。
 *
 * <p>不写成 /**：那会把 /api/** 的轮询和 /healthz 也算成访问，
 * 而这两类请求一天能产生几千次，统计出来的就不叫「访客」了。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final VisitInterceptor visitInterceptor;

    public WebConfig(VisitInterceptor visitInterceptor) {
        this.visitInterceptor = visitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(visitInterceptor)
                .addPathPatterns("/", "/index.html");
    }
}

package negocut.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;
import negocut.auth.security.TradeRestrictionInterceptor;
import negocut.common.idempotency.IdempotencyInterceptor;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final TradeRestrictionInterceptor tradeRestrictionInterceptor;
    private final IdempotencyInterceptor idempotencyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 순서가 중요하다. 거래 제한으로 거부될 요청이 중복 요청 방지의 처리 중 표시를 남기지 않도록 먼저 검사한다.
        registry.addInterceptor(tradeRestrictionInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(idempotencyInterceptor).addPathPatterns("/api/**");
    }
}

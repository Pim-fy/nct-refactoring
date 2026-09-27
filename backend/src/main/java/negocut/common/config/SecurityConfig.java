package negocut.common.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.Customizer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())  // CorsConfigurationSource 타입 빈을 컨테이너에서 자동으로 찾아 사용
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))  // 세션 미사용, JWT로 매 요청 인증
            .formLogin(f -> f.disable())    // 기본 로그인 폼 끔
            .httpBasic(b -> b.disable())    // HttpBasic 인증 끔
            // URL별 접근 권한 규칙
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/admin/**").hasRole("ADMIN")  // 권한 필요
                .anyRequest().authenticated())  // anyRequest로 나머지를 받음
            .exceptionHandling(e -> e
                .authenticationEntryPoint(authenticationEntryPoint) // 인증 실패
                .accessDeniedHandler(accessDeniedHandler)); // 권한 부족

        return http.build();    // SecurityFilterChain을 만들어 반환
    }

    // CORS 세부 규칙 정의
    // `.cors(Customizer.withDefaults())` 가 해당 빈을 찾아 사용함
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration config = new CorsConfiguration(); // 규칙 묶음(CorsConfiguration)
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));  // 허용할 HTTP 메서드
        config.setAllowedHeaders(List.of("Authorization", "Content-Type")); // 프런트에서 보낼 수 있는 헤더
        config.setAllowCredentials(true);   // 쿠키 사용

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource(); // URL 패턴별로 CORS 규칙을 매핑해서 저장하는 저장소(UrlBasedCorsConfigurationSource)
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}

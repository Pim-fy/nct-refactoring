package negocut.common.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.Customizer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import lombok.RequiredArgsConstructor;
import negocut.auth.security.JwtAuthenticationFilter;
import negocut.auth.token.JwtTokenProvider;
import negocut.member.repository.MemberRepository;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final JwtTokenProvider jwtTokenProvider;
    private final MemberRepository memberRepository;

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
                .requestMatchers(HttpMethod.GET, "/api/agreements").permitAll()  // 공개: 회원가입 화면의 약관 목록
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh").permitAll()  // 공개: 로그인, 토큰 재발급 (로그아웃은 로그인한 회원만)
                .requestMatchers(HttpMethod.POST, "/api/members").permitAll()  // 공개: 회원가입
                .requestMatchers(HttpMethod.GET, "/api/members/check-login-id", "/api/members/check-nickname", "/api/members/check-email").permitAll()  // 공개: 중복 확인
                .requestMatchers(HttpMethod.POST, "/api/auth/email-codes", "/api/auth/email-codes/verify").permitAll()  // 공개: 이메일 인증번호 발송·검증
                .requestMatchers("/api/admin/**").hasRole("ADMIN")  // 권한 필요
                .anyRequest().authenticated())  // anyRequest로 나머지를 받음
            // 액세스 토큰 쿠키로 로그인한 회원을 인증하고, 회원의 현재 상태·역할을 요청마다 DB에서 읽는다. 기본 로그인 필터보다 먼저 실행한다.
            .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider, memberRepository), UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(e -> e
                .authenticationEntryPoint(authenticationEntryPoint) // 인증 실패
                .accessDeniedHandler(accessDeniedHandler)); // 권한 부족

        return http.build();    // SecurityFilterChain을 만들어 반환
    }

    // 비밀번호는 BCrypt 해시로 저장한다.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
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

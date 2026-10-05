package negocut.auth.token;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.member.entity.MemberRole;

// 로그인 후 쓰는 액세스 토큰과 리프레시 토큰을 만들고 검증한다. (기능 명세서 1-3)
// 이메일 인증 완료를 증명하는 VerificationTokenProvider와는 서명 키도 용도도 다르다.
@Component
public class JwtTokenProvider {

    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_ROLE = "role";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    public record AccessClaims(Long memberId, MemberRole role) {
    }

    private final SecretKey key;
    private final Duration accessValidity;
    private final Duration refreshValidity;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-minutes:30}") long accessMinutes,
            @Value("${app.jwt.refresh-days:14}") long refreshDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessValidity = Duration.ofMinutes(accessMinutes);
        this.refreshValidity = Duration.ofDays(refreshDays);
    }

    public Duration accessValidity() {
        return accessValidity;
    }

    public Duration refreshValidity() {
        return refreshValidity;
    }

    public String createAccessToken(Long memberId, MemberRole role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessValidity)))
                .signWith(key)
                .compact();
    }

    // 같은 회원이 같은 시각에 여러 번 로그인해도 토큰이 서로 달라지도록 jti를 넣는다.
    public String createRefreshToken(Long memberId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshValidity)))
                .signWith(key)
                .compact();
    }

    // 요청마다 호출되므로 실패해도 예외 대신 빈 값을 돌려준다. 빈 값이면 인증되지 않은 요청으로 처리된다.
    public Optional<AccessClaims> parseAccessToken(String token) {
        try {
            Claims claims = parse(token);
            if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
                return Optional.empty();
            }
            return Optional.of(new AccessClaims(
                    Long.valueOf(claims.getSubject()),
                    MemberRole.valueOf(claims.get(CLAIM_ROLE, String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // 리프레시 토큰이 가리키는 회원 식별자를 돌려준다. 서명·만료·종류가 맞지 않으면 거부한다.
    public Long parseRefreshToken(String token) {
        try {
            Claims claims = parse(token);
            if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
                throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
            }
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}

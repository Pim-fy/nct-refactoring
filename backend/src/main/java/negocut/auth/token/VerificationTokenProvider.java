package negocut.auth.token;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import negocut.auth.entity.VerificationPurpose;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;

// 이메일 인증 완료를 증명하는 검증 토큰. 이메일·용도·만료 시각·인증 이력 식별자를 담아 서버가 서명한다. (기능 명세서 3-2)
@Component
public class VerificationTokenProvider {

    public static final long VALID_MINUTES = 10;

    private static final String CLAIM_PURPOSE = "purpose";
    private static final String CLAIM_VERIFICATION_ID = "vid";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final SecretKey key;

    public VerificationTokenProvider(@Value("${app.verification-token.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(String email, VerificationPurpose purpose, Long verificationId, LocalDateTime expiresAt) {
        return Jwts.builder()
                .subject(email)
                .claim(CLAIM_PURPOSE, purpose.name())
                .claim(CLAIM_VERIFICATION_ID, verificationId)
                .expiration(Date.from(expiresAt.atZone(ZONE).toInstant()))
                .signWith(key)
                .compact();
    }

    // 서명·만료가 유효하고 이메일과 용도가 일치할 때만 토큰이 가리키는 인증 이력 식별자를 돌려준다.
    public Long parse(String token, String email, VerificationPurpose purpose) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();

            if (!email.equals(claims.getSubject()) || !purpose.name().equals(claims.get(CLAIM_PURPOSE, String.class))) {
                throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_INVALID);
            }
            return claims.get(CLAIM_VERIFICATION_ID, Long.class);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_INVALID);
        }
    }
}

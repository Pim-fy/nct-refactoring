package negocut.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import negocut.auth.entity.VerificationPurpose;
import negocut.auth.token.VerificationTokenProvider;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;

// 스프링 없이 검증 토큰의 유효성 판단만 시험한다. (테스트 계획서 2-2 검증 토큰)
class VerificationTokenProviderTest {

    private static final String SECRET = "test-only-verification-token-key-0123456789abcdef";
    private static final String EMAIL = "user@example.com";

    private final VerificationTokenProvider provider = new VerificationTokenProvider(SECRET);

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_TOKEN_INVALID);
    }

    @Test
    void 유효한_토큰은_인증_이력_식별자를_돌려준다() {
        String token = provider.issue(EMAIL, VerificationPurpose.SIGN_UP, 7L, LocalDateTime.now().plusMinutes(10));

        assertThat(provider.parse(token, EMAIL, VerificationPurpose.SIGN_UP)).isEqualTo(7L);
    }

    @Test
    void 만료된_토큰은_거부한다() {
        String token = provider.issue(EMAIL, VerificationPurpose.SIGN_UP, 7L, LocalDateTime.now().minusMinutes(1));

        assertInvalid(() -> provider.parse(token, EMAIL, VerificationPurpose.SIGN_UP));
    }

    @Test
    void 용도가_다른_토큰은_거부한다() {
        String token = provider.issue(EMAIL, VerificationPurpose.SIGN_UP, 7L, LocalDateTime.now().plusMinutes(10));

        assertInvalid(() -> provider.parse(token, EMAIL, VerificationPurpose.RESET_PASSWORD));
    }

    @Test
    void 이메일이_다른_토큰은_거부한다() {
        String token = provider.issue(EMAIL, VerificationPurpose.SIGN_UP, 7L, LocalDateTime.now().plusMinutes(10));

        assertInvalid(() -> provider.parse(token, "other@example.com", VerificationPurpose.SIGN_UP));
    }

    @Test
    void 다른_키로_서명한_토큰과_형식이_아닌_문자열은_거부한다() {
        VerificationTokenProvider other = new VerificationTokenProvider("another-verification-token-key-0123456789abcdef");
        String forged = other.issue(EMAIL, VerificationPurpose.SIGN_UP, 7L, LocalDateTime.now().plusMinutes(10));

        assertInvalid(() -> provider.parse(forged, EMAIL, VerificationPurpose.SIGN_UP));
        assertInvalid(() -> provider.parse("not-a-jwt", EMAIL, VerificationPurpose.SIGN_UP));
        assertInvalid(() -> provider.parse("", EMAIL, VerificationPurpose.SIGN_UP));
    }
}

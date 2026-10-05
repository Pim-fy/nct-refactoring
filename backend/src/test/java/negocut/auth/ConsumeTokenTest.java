package negocut.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import negocut.auth.entity.EmailVerification;
import negocut.auth.entity.VerificationPurpose;
import negocut.auth.repository.EmailVerificationRepository;
import negocut.auth.service.EmailVerificationService;
import negocut.auth.token.VerificationTokenProvider;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.support.TestSuffix;

// 검증 토큰은 한 번만 쓸 수 있다. 같은 토큰을 동시에 써도 한 번만 성공해야 한다. (테스트 계획서 2-2 검증 토큰)
@SpringBootTest
class ConsumeTokenTest {

    @Autowired private EmailVerificationService service;
    @Autowired private EmailVerificationRepository repository;
    @Autowired private VerificationTokenProvider tokenProvider;

    private record Issued(String email, Long id, String token) {
    }

    private Issued issue() {
        String email = "consume" + TestSuffix.next() + "@example.com";
        EmailVerification saved = repository.save(EmailVerification.create(
                email, VerificationPurpose.SIGN_UP, "123456", LocalDateTime.now().plusMinutes(5)));
        String token = tokenProvider.issue(email, VerificationPurpose.SIGN_UP, saved.getId(), LocalDateTime.now().plusMinutes(10));
        return new Issued(email, saved.getId(), token);
    }

    private void assertTokenInvalid(Runnable action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_TOKEN_INVALID);
    }

    @Test
    void 처음_쓰면_성공하고_사용_완료로_표시된다() {
        Issued issued = issue();

        service.consumeToken(issued.token(), issued.email(), VerificationPurpose.SIGN_UP);

        assertThat(repository.findById(issued.id()).orElseThrow().isUsed()).isTrue();
    }

    @Test
    void 다시_쓰면_거부한다() {
        Issued issued = issue();
        service.consumeToken(issued.token(), issued.email(), VerificationPurpose.SIGN_UP);

        assertTokenInvalid(() -> service.consumeToken(issued.token(), issued.email(), VerificationPurpose.SIGN_UP));
    }

    @Test
    void 가리키는_인증_이력이_없으면_거부한다() {
        String email = "ghost" + TestSuffix.next() + "@example.com";
        String token = tokenProvider.issue(email, VerificationPurpose.SIGN_UP, 987654321L, LocalDateTime.now().plusMinutes(10));

        assertTokenInvalid(() -> service.consumeToken(token, email, VerificationPurpose.SIGN_UP));
    }

    @Test
    void 동시에_여러_번_써도_한_번만_성공한다() throws Exception {
        Issued issued = issue();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    service.consumeToken(issued.token(), issued.email(), VerificationPurpose.SIGN_UP);
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            }));
        }
        go.countDown();

        int success = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                success++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
    }
}

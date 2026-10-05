package negocut.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// app.mail.enabled를 빠뜨리거나 잘못 적으면 시작을 거부하고, false로 명시했을 때만 개발용 콘솔 발송기가 켜진다.
class MailSenderSettingGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MailSenderSettingGuard.class, ConsoleVerificationMailSender.class);

    // Gradle이 모든 테스트에 app.mail.enabled=false를 시스템 속성으로 주므로, "설정이 없는 상태"는 빈 값으로 덮어써서 만든다.
    // 가드는 미지정과 빈 값을 같게 본다.
    @Test
    void 설정을_빠뜨리면_설명과_함께_시작을_거부한다() {
        runner.withPropertyValues("app.mail.enabled=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("app.mail.enabled");
        });
    }

    @Test
    void 잘못된_값도_시작을_거부한다() {
        runner.withPropertyValues("app.mail.enabled=maybe").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("app.mail.enabled");
        });
    }

    @Test
    void false로_명시하면_콘솔_발송기가_켜진다() {
        runner.withPropertyValues("app.mail.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(VerificationMailSender.class);
            assertThat(context.getBean(VerificationMailSender.class)).isInstanceOf(ConsoleVerificationMailSender.class);
        });
    }

    @Test
    void true이면_가드도_콘솔_발송기도_만들지_않는다() {
        runner.withPropertyValues("app.mail.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(VerificationMailSender.class);   // SMTP 발송기는 이 시험의 구성에 넣지 않았다.
        });
    }
}

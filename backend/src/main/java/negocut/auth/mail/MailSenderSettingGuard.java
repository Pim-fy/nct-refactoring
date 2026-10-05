package negocut.auth.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// app.mail.enabled를 true나 false로 반드시 정하게 한다.
// 정하지 않은 채 실행하면 개발용 콘솔 발송기가 조용히 켜져 운영에서도 인증번호가 로그에 남을 수 있어, 설명과 함께 시작을 거부한다.
@Configuration
public class MailSenderSettingGuard {

    @Bean
    @ConditionalOnExpression("!{'true', 'false'}.contains('${app.mail.enabled:}')")
    public VerificationMailSender mailSenderSettingMissing() {
        throw new IllegalStateException(
                "app.mail.enabled를 지정해 주세요. true이면 SMTP로 메일을 보내고(spring.mail.* 설정 필요), "
                        + "false이면 메일 대신 콘솔에 인증번호를 출력합니다(개발용).");
    }
}

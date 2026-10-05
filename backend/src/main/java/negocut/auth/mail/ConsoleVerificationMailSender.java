package negocut.auth.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

// 개발용. app.mail.enabled 를 false로 명시했을 때만 메일 대신 콘솔에 인증번호를 출력한다. (설정을 빠뜨린 경우는 MailSenderSettingGuard가 시작을 막는다.)
// 인증번호는 로그에 남기지 않는 것이 원칙이므로(기능 명세서 4-3) 운영에서는 enabled=true 로 쓴다.
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "false")
public class ConsoleVerificationMailSender implements VerificationMailSender {

    @Override
    public void send(String email, String code) {
        log.warn("[Mail:DEV ONLY] {} 인증번호: {}", email, code);
    }
}

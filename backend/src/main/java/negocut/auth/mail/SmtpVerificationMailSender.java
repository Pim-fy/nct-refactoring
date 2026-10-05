package negocut.auth.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;

// app.mail.enabled=true 일 때만 사용한다. spring.mail.* 에 SMTP 서버 정보가 필요하다.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
public class SmtpVerificationMailSender implements VerificationMailSender {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String from;

    @Override
    public void send(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("[NCT] 이메일 인증번호");
        message.setText("인증번호는 [" + code + "] 입니다. 5분 안에 입력해 주세요.");

        try {
            mailSender.send(message);
        } catch (RuntimeException e) {
            log.warn("[Mail] 인증 메일 발송 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.EMAIL_SEND_FAILED);
        }
    }
}

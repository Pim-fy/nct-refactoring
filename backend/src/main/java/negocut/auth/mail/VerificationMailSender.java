package negocut.auth.mail;

public interface VerificationMailSender {

    // 발송에 실패하면 BusinessException(EMAIL_SEND_FAILED)을 던진다.
    void send(String email, String code);
}

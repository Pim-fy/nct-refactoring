package negocut.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import negocut.auth.entity.VerificationPurpose;
import negocut.common.util.Emails;

public record EmailCodeVerifyRequest(
        @NotBlank @Email @Size(max = 100) String email,
        @NotNull VerificationPurpose purpose,
        @NotBlank @Pattern(regexp = "\\d{6}") String code) {

    // 이메일은 대소문자를 구분하지 않으므로 받는 즉시 공백을 없애고 소문자로 통일한다.
    public EmailCodeVerifyRequest {
        email = Emails.normalize(email);
    }

}

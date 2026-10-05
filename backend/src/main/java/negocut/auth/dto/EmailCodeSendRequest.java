package negocut.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import negocut.auth.entity.VerificationPurpose;
import negocut.common.util.Emails;

// nickname은 아이디 찾기(FIND_ID), loginId는 비밀번호 재설정(RESET_PASSWORD)에서 필요하다.
// 용도별 필수 여부는 Service에서 확인한다. 회원가입(SIGN_UP)에서는 두 값을 무시한다. (API-4)
public record EmailCodeSendRequest(
        @NotBlank @Email @Size(max = 100) String email,
        @NotNull VerificationPurpose purpose,
        @Size(max = 10) String nickname,
        @Size(max = 20) String loginId) {

    // 이메일은 대소문자를 구분하지 않으므로 받는 즉시 공백을 없애고 소문자로 통일한다.
    public EmailCodeSendRequest {
        email = Emails.normalize(email);
    }

}

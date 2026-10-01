package negocut.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import negocut.auth.entity.VerificationPurpose;

// nickname은 아이디 찾기(FIND_ID), loginId는 비밀번호 재설정(RESET_PASSWORD)에서 필요하다.
// 용도별 필수 여부는 Service에서 확인한다. 회원가입(SIGN_UP)에서는 두 값을 무시한다. (API-4)
public record EmailCodeSendRequest(
        @NotBlank @Email @Size(max = 100) String email,
        @NotNull VerificationPurpose purpose,
        @Size(max = 10) String nickname,
        @Size(max = 20) String loginId) {
}

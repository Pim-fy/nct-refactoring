package negocut.member.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import negocut.common.util.Emails;

// 형식 규칙은 기능 명세서 3-1 회원가입. 형식 오류 메시지는 API 명세서 0-3의 예시 문구를 따른다.
public record SignUpRequest(

        @NotBlank(message = "아이디를 입력해 주세요.")
        @Pattern(regexp = MemberPatterns.LOGIN_ID, message = MemberPatterns.LOGIN_ID_MESSAGE)
        String loginId,

        // BCrypt는 72바이트까지만 다루므로 상한을 둔다.
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$", message = "8자 이상이며 영문과 숫자를 포함해야 합니다.")
        String password,

        @NotBlank(message = "닉네임을 입력해 주세요.")
        @Pattern(regexp = MemberPatterns.NICKNAME, message = MemberPatterns.NICKNAME_MESSAGE)
        String nickname,

        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 100, message = "이메일은 100자 이하여야 합니다.")
        String email,

        // 형식은 명세서에 없어 숫자와 하이픈만 허용한다.
        @NotBlank(message = "연락처를 입력해 주세요.")
        @Pattern(regexp = "^[0-9-]{9,20}$", message = "숫자와 하이픈으로 9~20자여야 합니다.")
        String phone,

        @NotBlank(message = "이메일 인증이 필요합니다.")
        String verificationToken,

        @NotEmpty(message = "약관 동의 정보가 필요합니다.")
        @Valid
        List<AgreementConsent> agreements) {

    // 이메일은 대소문자를 구분하지 않으므로 받는 즉시 공백을 없애고 소문자로 통일한다.
    public SignUpRequest {
        email = Emails.normalize(email);
    }


    // isAgreed를 생략하면 동의하지 않은 것으로 본다. 필수 약관이면 가입이 거부되고, 선택 약관이면 미동의로 기록된다.
    public record AgreementConsent(
            @NotNull(message = "약관 식별자가 필요합니다.") Long agreementId,
            Boolean isAgreed) {

        public boolean agreed() {
            return Boolean.TRUE.equals(isAgreed);
        }
    }
}

package negocut.auth.dto;

import jakarta.validation.constraints.NotBlank;

// 형식 검사는 하지 않는다. 틀린 값은 아이디와 비밀번호 중 무엇이 틀렸는지 알리지 않고 LOGIN_FAILED로 응답한다.
// isKeepLogin은 생략할 수 있고 생략하면 해제로 본다. (기능 명세서 3-2 로그인 옵션: 기본값은 해제)
public record LoginRequest(
        @NotBlank String loginId,
        @NotBlank String password,
        Boolean isKeepLogin) {

    public boolean keepLogin() {
        return Boolean.TRUE.equals(isKeepLogin);
    }
}

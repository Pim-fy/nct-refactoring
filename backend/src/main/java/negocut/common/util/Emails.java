package negocut.common.util;

import java.util.Locale;

// 이메일은 대소문자를 구분하지 않고 쓰므로 받는 시점에 공백을 없애고 소문자로 통일한다.
// 인증번호 발송·검증, 검증 토큰, 가입, 중복 확인이 모두 같은 값으로 비교되게 하기 위해서다.
public final class Emails {

    private Emails() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}

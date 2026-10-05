package negocut.auth.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 거래 제한 회원도 호출할 수 있는 쓰기 요청(POST·PUT·PATCH·DELETE)에 붙인다.
// 인증이 필요한 쓰기 요청은 기본적으로 정상 상태의 회원만 허용하므로, 이 표시가 없으면 거래 제한 회원은 403 MEMBER_TRADE_RESTRICTED를 받는다.
// 대상: 로그아웃, 본인 정보 관리, 탈퇴, 진행 중인 거래 마무리 (기능 명세서 2-2)
// 공개 경로의 쓰기 요청(로그인, 재발급, 가입, 이메일 인증번호)에도 붙인다. 이 경로들은 인증이 필요 없지만, 브라우저에 거래 제한 회원의
// 유효한 쿠키가 남아 있으면 필터가 그 쿠키로 인증해 기본 검사에 걸리기 때문이다.
// 조회(GET)는 항상 허용되므로 붙일 필요가 없다.
// 가능하면 메서드마다 붙인다. 클래스에 붙이면 그 클래스에 나중에 추가되는 쓰기 메서드도 자동으로 허용되어 기본 차단이 풀린다.
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowTradeRestricted {
}

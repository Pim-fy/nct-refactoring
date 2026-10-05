package negocut.common.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 전역 중복 요청 방지에서 제외한다. 붙은 메서드(또는 컨트롤러 클래스)는 지문 판정을 하지 않는다.
// 제외 대상: 쿠키를 내려주는 응답(로그인, 토큰 재발급, 로그아웃), 멀티파트 파일 업로드,
//           같은 요청을 다시 보내도 새로 판정해야 하는 API(실패 횟수를 세는 API 등)
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipIdempotency {
}

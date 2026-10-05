package negocut.auth.security;

import java.util.Set;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.member.entity.MemberStatus;

// 인증된 회원의 쓰기 요청(POST·PUT·PATCH·DELETE)은 기본적으로 정상 상태의 회원만 허용한다. (기능 명세서 2-2)
// 거래 제한 회원도 할 수 있는 요청(로그아웃, 본인 정보 관리, 탈퇴, 진행 중인 거래 마무리)에는 @AllowTradeRestricted를 붙인다.
// 조회(GET 등)는 상태와 무관하게 허용한다. 인증되지 않은 요청은 검사하지 않는다.
// 공개 경로의 쓰기 요청도 유효한 쿠키가 실려 오면 인증된 요청이 되므로, 로그인·가입 같은 API에는 @AllowTradeRestricted를 붙여 둔다.
// 로그인 정지·탈퇴 회원은 인증 필터에서 이미 걸러지므로 여기에는 오지 않는다.
// 상태는 인증 필터가 요청마다 읽어 인증 주체에 담은 값을 쓰므로 추가 조회가 없다.
@Component
public class TradeRestrictionInterceptor implements HandlerInterceptor {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        if (!WRITE_METHODS.contains(request.getMethod()) || isAllowed(handlerMethod)) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthMember member
                && member.status() != MemberStatus.NORMAL) {
            throw new BusinessException(ErrorCode.MEMBER_TRADE_RESTRICTED);
        }
        return true;
    }

    // 메서드와 클래스를 같은 규칙으로 찾는다. 메타 어노테이션과 상위 클래스·인터페이스에 선언된 표시도 인정한다.
    private boolean isAllowed(HandlerMethod handlerMethod) {
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), AllowTradeRestricted.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), AllowTradeRestricted.class);
    }
}

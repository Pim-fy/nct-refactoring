package negocut.auth.security;

import org.springframework.web.bind.annotation.PostMapping;

import negocut.common.response.ApiResponse;

// 인터페이스 메서드에 선언한 @AllowTradeRestricted도 인식되는지 시험하기 위한 테스트 전용 인터페이스
public interface TradeRestrictionTestApi {

    @PostMapping("/iface")
    @AllowTradeRestricted
    ApiResponse<Void> postFromInterface();
}

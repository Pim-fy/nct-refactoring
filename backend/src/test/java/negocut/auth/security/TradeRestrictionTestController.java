package negocut.auth.security;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import negocut.common.response.ApiResponse;

// 거래 제한 검사를 시험하기 위한 테스트 전용 컨트롤러. 실제 쓰기 API가 생기기 전에 규칙을 검증한다.
@RestController
@RequestMapping("/api/test/trade")
public class TradeRestrictionTestController {

    @GetMapping("/read")
    public ApiResponse<Void> read() {
        return ApiResponse.success();
    }

    @PostMapping("/default")
    public ApiResponse<Void> postDefault() {
        return ApiResponse.success();
    }

    @PutMapping("/default")
    public ApiResponse<Void> putDefault() {
        return ApiResponse.success();
    }

    @PatchMapping("/default")
    public ApiResponse<Void> patchDefault() {
        return ApiResponse.success();
    }

    @DeleteMapping("/default")
    public ApiResponse<Void> deleteDefault() {
        return ApiResponse.success();
    }

    @PostMapping("/allowed")
    @AllowTradeRestricted
    public ApiResponse<Void> postAllowed() {
        return ApiResponse.success();
    }

    @RestController
    @RequestMapping("/api/test/trade-iface")
    public static class InterfaceController implements TradeRestrictionTestApi {

        @Override
        public ApiResponse<Void> postFromInterface() {
            return ApiResponse.success();
        }
    }

    @RestController
    @RequestMapping("/api/test/trade-class")
    @AllowTradeRestricted
    public static class AllowedClassController {

        @PostMapping("/write")
        public ApiResponse<Void> write() {
            return ApiResponse.success();
        }
    }
}

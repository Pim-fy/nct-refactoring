package negocut.common.exception;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import negocut.common.response.ApiResponse;

// 필수 파라미터 누락과 타입 불일치 응답을 시험하기 위한 테스트 전용 컨트롤러
@RestController
@RequestMapping("/api/test/exceptions")
public class ExceptionTestController {

    @GetMapping("/param")
    public ApiResponse<Integer> param(@RequestParam int n) {
        return ApiResponse.success(n);
    }
}

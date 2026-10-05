package negocut.common.idempotency;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.common.response.ApiResponse;

// 전역 중복 요청 방지를 시험하기 위한 테스트 전용 컨트롤러. 호출될 때마다 처리 횟수를 센다.
@RestController
@RequestMapping("/api/test/idempotency")
public class IdempotencyTestController {

    static final AtomicInteger EXECUTIONS = new AtomicInteger();

    static void reset() {
        EXECUTIONS.set(0);
    }

    @PostMapping("/count")
    public ApiResponse<Integer> count(@RequestBody(required = false) String body) {
        return ApiResponse.success(EXECUTIONS.incrementAndGet());
    }

    @PostMapping("/skip")
    @SkipIdempotency
    public ApiResponse<Integer> skip(@RequestBody(required = false) String body) {
        return ApiResponse.success(EXECUTIONS.incrementAndGet());
    }

    @PostMapping("/fail")
    public ApiResponse<Integer> fail(@RequestBody(required = false) String body) {
        EXECUTIONS.incrementAndGet();
        throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
    }

    @PostMapping("/slow")
    public ApiResponse<Integer> slow(@RequestBody(required = false) String body) throws InterruptedException {
        Thread.sleep(600);
        return ApiResponse.success(EXECUTIONS.incrementAndGet());
    }

    @DeleteMapping("/count")
    public ApiResponse<Integer> delete() {
        return ApiResponse.success(EXECUTIONS.incrementAndGet());
    }

    @GetMapping("/count")
    public ApiResponse<Integer> read() {
        return ApiResponse.success(EXECUTIONS.incrementAndGet());
    }
}

package negocut.auth.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import negocut.auth.dto.EmailCodeSendRequest;
import negocut.auth.dto.EmailCodeSendResponse;
import negocut.auth.dto.EmailCodeVerifyRequest;
import negocut.auth.dto.EmailCodeVerifyResponse;
import negocut.auth.security.AllowTradeRestricted;
import negocut.auth.service.EmailVerificationService;
import negocut.common.response.ApiResponse;

// 로그아웃 상태에서 쓰는 공개 API다. 거래 제한 회원의 쿠키가 브라우저에 남아 있어도 막히지 않도록 메서드마다 @AllowTradeRestricted를 붙인다.
// 클래스가 아니라 메서드마다 붙여서, 이 클래스에 인증이 필요한 쓰기 API가 추가되면 기본대로 막히게 한다.
@RestController
@RequestMapping("/api/auth/email-codes")
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    @PostMapping
    @AllowTradeRestricted
    public ApiResponse<EmailCodeSendResponse> sendCode(@Valid @RequestBody EmailCodeSendRequest request) {
        return ApiResponse.success(emailVerificationService.sendCode(request));
    }

    @PostMapping("/verify")
    @AllowTradeRestricted
    public ApiResponse<EmailCodeVerifyResponse> verifyCode(@Valid @RequestBody EmailCodeVerifyRequest request) {
        return ApiResponse.success(emailVerificationService.verifyCode(request));
    }
}

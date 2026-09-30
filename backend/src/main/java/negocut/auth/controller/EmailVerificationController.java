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
import negocut.auth.service.EmailVerificationService;
import negocut.common.response.ApiResponse;

@RestController
@RequestMapping("/api/auth/email-codes")
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    @PostMapping
    public ApiResponse<EmailCodeSendResponse> sendCode(@Valid @RequestBody EmailCodeSendRequest request) {
        return ApiResponse.success(emailVerificationService.sendCode(request));
    }

    @PostMapping("/verify")
    public ApiResponse<EmailCodeVerifyResponse> verifyCode(@Valid @RequestBody EmailCodeVerifyRequest request) {
        return ApiResponse.success(emailVerificationService.verifyCode(request));
    }
}

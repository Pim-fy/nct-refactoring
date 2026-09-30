package negocut.member.controller;

import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import negocut.common.exception.ErrorCode;
import negocut.common.exception.InputInvalidException;
import negocut.common.response.ApiResponse;
import negocut.member.dto.AvailabilityResponse;
import negocut.member.dto.SignUpRequest;
import negocut.member.dto.SignUpResponse;
import negocut.member.service.MemberService;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    // BindingResult를 받으면 형식 오류가 예외로 바로 나가지 않고 여기로 넘어와서,
    // 회원 전용 에러 코드(MEMBER_INPUT_INVALID)와 필드별 사유(details)로 응답할 수 있다.
    @PostMapping
    public ApiResponse<SignUpResponse> signUp(@Valid @RequestBody SignUpRequest request, BindingResult bindingResult) {
        InputInvalidException.throwIfInvalid(bindingResult, ErrorCode.MEMBER_INPUT_INVALID);
        return ApiResponse.success(memberService.signUp(request));
    }

    // 파라미터가 없을 때도 500이 아니라 입력값 오류로 응답하도록 required=false로 받는다.
    @GetMapping("/check-login-id")
    public ApiResponse<AvailabilityResponse> checkLoginId(@RequestParam(required = false) String loginId) {
        return ApiResponse.success(memberService.checkLoginId(loginId));
    }

    @GetMapping("/check-nickname")
    public ApiResponse<AvailabilityResponse> checkNickname(@RequestParam(required = false) String nickname) {
        return ApiResponse.success(memberService.checkNickname(nickname));
    }

    @GetMapping("/check-email")
    public ApiResponse<AvailabilityResponse> checkEmail(@RequestParam(required = false) String email) {
        return ApiResponse.success(memberService.checkEmail(email));
    }
}

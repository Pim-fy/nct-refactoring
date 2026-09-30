package negocut.member.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import negocut.common.response.ApiResponse;
import negocut.member.dto.AgreementListResponse;
import negocut.member.service.AgreementService;

@RestController
@RequestMapping("/api/agreements")
@RequiredArgsConstructor
public class AgreementController {

    private final AgreementService agreementService;

    @GetMapping
    public ApiResponse<AgreementListResponse> getAgreements() {
        return ApiResponse.success(agreementService.getEffectiveAgreements());
    }
}

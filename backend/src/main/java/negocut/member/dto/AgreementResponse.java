package negocut.member.dto;

import negocut.member.entity.Agreement;
import negocut.member.entity.AgreementType;

public record AgreementResponse(
        Long agreementId,
        AgreementType agreementType,
        String version,
        String title,
        String content,
        boolean isRequired) {

    public static AgreementResponse from(Agreement agreement) {
        return new AgreementResponse(
                agreement.getId(),
                agreement.getAgreementType(),
                agreement.getVersion(),
                agreement.getTitle(),
                agreement.getContent(),
                agreement.isRequired());
    }
}

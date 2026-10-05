package negocut.member.service;

import java.time.LocalDateTime;
import java.util.Comparator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.member.dto.AgreementListResponse;
import negocut.member.dto.AgreementResponse;
import negocut.member.repository.AgreementRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgreementService {

    private final AgreementRepository agreementRepository;

    // 시행 중인 약관을 유형 순서(AgreementType 선언 순서)로 반환한다.
    // DB 정렬은 enum을 문자열로 저장해 이름순이 되므로 선언 순서로 자바에서 정렬한다.
    public AgreementListResponse getEffectiveAgreements() {
        return new AgreementListResponse(
                agreementRepository.findByActiveTrueAndEffectiveAtLessThanEqual(LocalDateTime.now()).stream()
                        .sorted(Comparator.comparing(agreement -> agreement.getAgreementType()))
                        .map(agreement -> AgreementResponse.from(agreement))
                        .toList());
    }
}

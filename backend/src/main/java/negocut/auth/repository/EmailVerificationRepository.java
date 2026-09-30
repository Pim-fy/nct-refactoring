package negocut.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import negocut.auth.entity.EmailVerification;
import negocut.auth.entity.VerificationPurpose;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    // 같은 이메일·용도로 여러 번 발송해도 가장 최근에 발송한 인증 이력만 검증 대상이다.
    Optional<EmailVerification> findFirstByEmailAndPurposeOrderByIdDesc(String email, VerificationPurpose purpose);
}

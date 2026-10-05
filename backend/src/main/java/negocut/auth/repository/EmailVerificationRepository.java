package negocut.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import negocut.auth.entity.EmailVerification;
import negocut.auth.entity.VerificationPurpose;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    // 같은 이메일·용도로 여러 번 발송해도 가장 최근에 발송한 인증 이력만 검증 대상이다.
    Optional<EmailVerification> findFirstByEmailAndPurposeOrderByIdDesc(String email, VerificationPurpose purpose);

    // 아직 사용하지 않은 이력만 사용 완료로 바꾸고 바뀐 행 수를 돌려준다. 0이면 이미 쓴 이력이다.
    // 읽고 나서 바꾸는 방식이면 같은 토큰을 동시에 쓰는 두 요청이 모두 "안 썼다"고 볼 수 있어, 조건을 붙여 한 번에 갱신한다.
    @Modifying
    @Query("update EmailVerification v set v.used = true where v.id = :id and v.used = false")
    int markUsedIfUnused(@Param("id") Long id);
}

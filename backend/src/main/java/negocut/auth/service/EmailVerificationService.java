package negocut.auth.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.auth.dto.EmailCodeSendRequest;
import negocut.auth.dto.EmailCodeSendResponse;
import negocut.auth.dto.EmailCodeVerifyRequest;
import negocut.auth.dto.EmailCodeVerifyResponse;
import negocut.auth.entity.EmailVerification;
import negocut.auth.entity.VerificationPurpose;
import negocut.auth.mail.VerificationMailSender;
import negocut.auth.repository.EmailVerificationRepository;
import negocut.auth.token.VerificationTokenProvider;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final long CODE_VALID_MINUTES = 5;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationRepository verificationRepository;
    private final VerificationMailSender mailSender;
    private final VerificationTokenProvider tokenProvider;

    // 인증 이력은 저장(커밋)한 뒤 메일을 보낸다. 메일 발송에 실패해도 이력은 남고, 사용자는 재발송한다. (기능 명세서 3-2)
    // 그래서 이 메서드 전체를 하나의 트랜잭션으로 묶지 않는다.
    public EmailCodeSendResponse sendCode(EmailCodeSendRequest request) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(CODE_VALID_MINUTES);

        verificationRepository.save(EmailVerification.create(request.email(), request.purpose(), code, expiresAt));
        mailSender.send(request.email(), code);

        return new EmailCodeSendResponse(toOffset(expiresAt));
    }

    // 번호 불일치·이미 사용 → 만료 순서로 확인한다. 사용 완료 표시는 토큰을 쓰는 요청이 끝날 때 한다.
    @Transactional(readOnly = true)
    public EmailCodeVerifyResponse verifyCode(EmailCodeVerifyRequest request) {
        EmailVerification verification = verificationRepository
                .findFirstByEmailAndPurposeOrderByIdDesc(request.email(), request.purpose())
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID));

        if (!verification.getCode().equals(request.code()) || verification.isUsed()) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        if (verification.isExpired(now)) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }

        LocalDateTime tokenExpiresAt = now.plusMinutes(VerificationTokenProvider.VALID_MINUTES);
        String token = tokenProvider.issue(request.email(), request.purpose(), verification.getId(), tokenExpiresAt);

        return new EmailCodeVerifyResponse(token, toOffset(tokenExpiresAt));
    }

    // 검증 토큰을 쓰는 요청(회원가입 등)이 호출한다. 호출한 쪽의 트랜잭션에 참여하므로,
    // 사용 완료 표시는 그 요청의 나머지 작업(계정 생성 등)과 함께 커밋되거나 함께 취소된다.
    @Transactional
    public void consumeToken(String token, String email, VerificationPurpose purpose) {
        Long verificationId = tokenProvider.parse(token, email, purpose);

        EmailVerification verification = verificationRepository.findById(verificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_INVALID));

        if (verification.isUsed()) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_INVALID);
        }
        verification.markUsed();
    }

    private OffsetDateTime toOffset(LocalDateTime time) {
        return time.atZone(ZONE).toOffsetDateTime();
    }
}

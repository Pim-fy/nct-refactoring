package negocut.auth.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

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
import negocut.common.exception.InputInvalidException;
import negocut.common.response.ErrorResult;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final long CODE_VALID_MINUTES = 5;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationRepository verificationRepository;
    private final MemberRepository memberRepository;
    private final VerificationMailSender mailSender;
    private final VerificationTokenProvider tokenProvider;

    // 인증 이력은 저장(커밋)한 뒤 메일을 보낸다. 메일 발송에 실패해도 이력은 남고, 사용자는 재발송한다. (기능 명세서 3-2)
    // 그래서 이 메서드 전체를 하나의 트랜잭션으로 묶지 않는다.
    public EmailCodeSendResponse sendCode(EmailCodeSendRequest request) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(CODE_VALID_MINUTES);

        // 일치하는 계정이 없으면 이력도 메일도 만들지 않지만, 응답은 발송했을 때와 같은 형태로 돌려준다.
        // 그래야 응답만 보고 계정 존재 여부를 알 수 없다. (기능 명세서 3-2, API-4)
        if (!isAccountMatched(request)) {
            return new EmailCodeSendResponse(toOffset(expiresAt));
        }

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

        // 사용 여부 확인과 사용 표시를 한 번의 조건부 갱신으로 처리한다. 없는 이력이거나 이미 쓴 이력이면 갱신된 행이 0이다.
        if (verificationRepository.markUsedIfUnused(verificationId) == 0) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_INVALID);
        }
    }

    // 용도별로 계정 일치를 확인한다. 회원가입은 확인할 계정이 없어 항상 통과한다.
    // 아이디 찾기는 닉네임+이메일, 비밀번호 재설정은 로그인 ID+이메일이 모두 맞는 계정이 있어야 한다.
    // 필요한 값이 빠진 요청은 계정 존재 여부와 무관한 입력 오류라 400으로 알린다.
    private boolean isAccountMatched(EmailCodeSendRequest request) {
        return switch (request.purpose()) {
            case SIGN_UP -> true;
            case FIND_ID -> memberRepository.existsByNicknameAndEmailAndMemberStatusNot(
                    requireText("nickname", request.nickname()), request.email(), MemberStatus.WITHDRAWN);
            case RESET_PASSWORD -> memberRepository.existsByLoginIdAndEmailAndMemberStatusNot(
                    requireText("loginId", request.loginId()), request.email(), MemberStatus.WITHDRAWN);
        };
    }

    private String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new InputInvalidException(ErrorCode.INVALID_INPUT_VALUE,
                    List.of(new ErrorResult.FieldError(field, "필수 입력값입니다.")));
        }
        return value;
    }

    private OffsetDateTime toOffset(LocalDateTime time) {
        return time.atZone(ZONE).toOffsetDateTime();
    }
}

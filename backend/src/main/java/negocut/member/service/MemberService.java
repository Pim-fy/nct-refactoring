package negocut.member.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.auth.entity.VerificationPurpose;
import negocut.auth.service.EmailVerificationService;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.common.exception.InputInvalidException;
import negocut.common.response.ErrorResult;
import negocut.common.util.Emails;
import negocut.member.dto.AvailabilityResponse;
import negocut.member.dto.MemberPatterns;
import negocut.member.dto.MyInfoResponse;
import negocut.member.dto.SignUpRequest;
import negocut.member.dto.SignUpResponse;
import negocut.member.entity.Agreement;
import negocut.member.entity.Member;
import negocut.member.entity.MemberAgreement;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.AgreementRepository;
import negocut.member.repository.MemberAgreementRepository;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;

@Service
@RequiredArgsConstructor
public class MemberService {

    private static final int BCRYPT_MAX_BYTES = 72;

    private final MemberRepository memberRepository;
    private final MemberAgreementRepository memberAgreementRepository;
    private final AgreementRepository agreementRepository;
    private final PointBalanceRepository pointBalanceRepository;
    private final EmailVerificationService emailVerificationService;
    private final PasswordEncoder passwordEncoder;

    // 처리 흐름: 중복 검사 → 약관 동의 확인 → 검증 토큰 확인 → 계정·약관 동의 이력·포인트 잔액 생성 (기능 명세서 3-1)
    // 검증 토큰의 사용 완료 표시까지 한 트랜잭션이라, 어느 단계든 실패하면 계정도 토큰 사용도 남지 않는다.
    @Transactional
    public SignUpResponse signUp(SignUpRequest request) {
        checkPasswordBytes(request.password());
        checkDuplicates(request);
        List<SignUpRequest.AgreementConsent> consents = request.agreements();
        List<Agreement> effectiveAgreements = validateAgreements(consents);

        emailVerificationService.consumeToken(request.verificationToken(), request.email(), VerificationPurpose.SIGN_UP);

        Member member = saveMember(Member.create(
                request.loginId(),
                passwordEncoder.encode(request.password()),
                request.nickname(),
                request.email(),
                request.phone()));

        LocalDateTime agreedAt = LocalDateTime.now();
        Map<Long, Boolean> agreedById = consents.stream()
                .collect(Collectors.toMap(consent -> consent.agreementId(), consent -> consent.agreed()));
        memberAgreementRepository.saveAll(effectiveAgreements.stream()
                .map(agreement -> MemberAgreement.create(member, agreement, agreedById.get(agreement.getId()), agreedAt))
                .toList());

        pointBalanceRepository.save(PointBalance.createEmpty(member));

        return new SignUpResponse(member.getId());
    }

    // 로그인한 회원의 현재 정보와 포인트. 화면이 로그인 상태를 복원할 때도 쓴다. (API 명세서 1-2)
    // 로그인 후에 정지·탈퇴된 회원은 액세스 토큰이 아직 유효해도 현재 상태를 기준으로 막는다.
    @Transactional(readOnly = true)
    public MyInfoResponse getMyInfo(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));

        MemberStatus status = member.getMemberStatus();
        if (status == MemberStatus.RESTRICTED_LOGIN || status == MemberStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.MEMBER_STATUS_NOT_ALLOWED);
        }

        // 가입할 때 포인트 잔액을 함께 만들므로 항상 있어야 한다.
        PointBalance balance = pointBalanceRepository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalStateException("포인트 잔액이 없는 회원입니다. memberId=" + memberId));
        return MyInfoResponse.of(member, balance);
    }

    public AvailabilityResponse checkLoginId(String loginId) {
        requireFormat("loginId", loginId, MemberPatterns.LOGIN_ID, MemberPatterns.LOGIN_ID_MESSAGE);
        return new AvailabilityResponse(!memberRepository.existsByLoginId(loginId));
    }

    public AvailabilityResponse checkNickname(String nickname) {
        requireFormat("nickname", nickname, MemberPatterns.NICKNAME, MemberPatterns.NICKNAME_MESSAGE);
        return new AvailabilityResponse(!memberRepository.existsByNickname(nickname));
    }

    public AvailabilityResponse checkEmail(String rawEmail) {
        String email = Emails.normalize(rawEmail);
        if (email == null || email.isBlank() || email.length() > 100 || !email.contains("@")) {
            throw fieldError("email", "이메일 형식이 올바르지 않습니다.");
        }
        return new AvailabilityResponse(!memberRepository.existsByEmail(email));
    }

    // 위의 중복 검사와 저장 사이에 같은 값으로 다른 요청이 먼저 저장되면 DB의 유니크 제약이 막는다.
    // 그 오류를 500이 아니라 어느 값이 중복인지 알려 주는 오류로 바꾼다. 이 예외로 트랜잭션은 취소되므로 계정도, 토큰 사용도 남지 않는다.
    private Member saveMember(Member member) {
        try {
            return memberRepository.saveAndFlush(member);   // 제약 위반을 여기서 바로 드러내려고 즉시 반영한다.
        } catch (DataIntegrityViolationException e) {
            ErrorCode duplicated = duplicateErrorCodeOf(e);
            if (duplicated == null) {
                throw e;
            }
            throw new BusinessException(duplicated);
        }
    }

    // 깨진 제약 이름으로 어느 값이 중복인지 판단한다. 알 수 없는 제약이면 null이다.
    static ErrorCode duplicateErrorCodeOf(DataIntegrityViolationException e) {
        String message = String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage()).toLowerCase();
        if (message.contains("uk_member_login_id")) {
            return ErrorCode.MEMBER_ID_DUPLICATED;
        }
        if (message.contains("uk_member_nickname")) {
            return ErrorCode.MEMBER_NICKNAME_DUPLICATED;
        }
        if (message.contains("uk_member_email")) {
            return ErrorCode.MEMBER_EMAIL_DUPLICATED;
        }
        return null;
    }

    // BCrypt는 72바이트까지만 다룬다. 형식 검사는 글자 수만 보므로, 한글처럼 한 글자가 여러 바이트인 비밀번호는 여기서 거른다.
    private void checkPasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw fieldError("password", "비밀번호가 너무 깁니다. 영문·숫자 기준 72자(한글은 24자) 이하여야 합니다.");
        }
    }

    private void checkDuplicates(SignUpRequest request) {
        if (memberRepository.existsByLoginId(request.loginId())) {
            throw new BusinessException(ErrorCode.MEMBER_ID_DUPLICATED);
        }
        if (memberRepository.existsByNickname(request.nickname())) {
            throw new BusinessException(ErrorCode.MEMBER_NICKNAME_DUPLICATED);
        }
        if (memberRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.MEMBER_EMAIL_DUPLICATED);
        }
    }

    // 요청의 약관 목록이 "시행 중인 약관 전체"와 정확히 같고, 필수 약관에 모두 동의했는지 확인한다.
    // 시행 중인 약관 목록을 반환한다. (API 명세서 1-2: 목록의 agreementId를 모두 담아 동의 여부와 함께 보낸다)
    private List<Agreement> validateAgreements(List<SignUpRequest.AgreementConsent> consents) {
        List<Agreement> effective = agreementRepository.findByActiveTrueAndEffectiveAtLessThanEqual(LocalDateTime.now());
        Map<Long, Agreement> effectiveById = effective.stream()
                .collect(Collectors.toMap(agreement -> agreement.getId(), Function.identity()));

        Set<Long> sentIds = new HashSet<>();
        for (SignUpRequest.AgreementConsent consent : consents) {
            if (!effectiveById.containsKey(consent.agreementId()) || !sentIds.add(consent.agreementId())) {
                throw fieldError("agreements", "시행 중인 약관이 아니거나 중복된 약관입니다.");
            }
        }
        if (sentIds.size() != effectiveById.size()) {
            throw fieldError("agreements", "시행 중인 모든 약관에 대한 동의 여부를 보내야 합니다.");
        }
        for (SignUpRequest.AgreementConsent consent : consents) {
            if (effectiveById.get(consent.agreementId()).isRequired() && !consent.agreed()) {
                throw fieldError("agreements", "필수 약관에 모두 동의해야 합니다.");
            }
        }
        return effective;
    }

    private void requireFormat(String field, String value, String regex, String message) {
        if (value == null || !Pattern.matches(regex, value)) {
            throw fieldError(field, message);
        }
    }

    private InputInvalidException fieldError(String field, String message) {
        List<ErrorResult.FieldError> details = new ArrayList<>();
        details.add(new ErrorResult.FieldError(field, message));
        return new InputInvalidException(ErrorCode.MEMBER_INPUT_INVALID, details);
    }
}

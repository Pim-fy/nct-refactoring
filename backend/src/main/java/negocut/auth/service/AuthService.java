package negocut.auth.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import negocut.auth.dto.LoginRequest;
import negocut.auth.dto.LoginResponse;
import negocut.auth.dto.RefreshResponse;
import negocut.auth.entity.RefreshToken;
import negocut.auth.repository.RefreshTokenRepository;
import negocut.auth.token.JwtTokenProvider;
import negocut.auth.token.TokenHasher;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.member.entity.Member;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;

@Service
public class AuthService {

    // BCrypt는 72바이트까지만 다룬다. 이보다 긴 비밀번호는 저장된 값과 같을 수 없다.
    private static final int BCRYPT_MAX_BYTES = 72;

    // 서비스가 발급한 토큰과 쿠키 설정을 컨트롤러가 그대로 쓸 수 있게 묶어 돌려준다.
    public record LoginResult(String accessToken, String refreshToken, boolean keepLogin, LoginResponse body) {
    }

    public record RefreshResult(String accessToken, RefreshResponse body) {
    }

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    // 없는 아이디로 로그인할 때도 비밀번호 비교를 한 번 하도록 둔 값. 응답 시간으로 아이디 존재 여부를 알 수 없게 한다.
    private final String dummyPasswordHash;

    public AuthService(MemberRepository memberRepository, RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder, JwtTokenProvider tokenProvider) {
        this.memberRepository = memberRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.dummyPasswordHash = passwordEncoder.encode("not-a-real-password");
    }

    // 처리 흐름: 자격 확인 → 회원 상태 확인 → 액세스·리프레시 토큰 발급 → 리프레시 토큰 해시 저장 (기능 명세서 3-2)
    @Transactional
    public LoginResult login(LoginRequest request) {
        Member member = memberRepository.findByLoginId(request.loginId()).orElse(null);

        if (!isPasswordMatched(member, request.password())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);   // 아이디와 비밀번호 중 무엇이 틀렸는지 알리지 않는다.
        }
        checkLoginAllowed(member);

        String accessToken = tokenProvider.createAccessToken(member.getId(), member.getMemberRole());
        String refreshToken = tokenProvider.createRefreshToken(member.getId());
        refreshTokenRepository.save(RefreshToken.create(
                member, TokenHasher.sha256(refreshToken), LocalDateTime.now().plus(tokenProvider.refreshValidity())));

        LoginResponse body = new LoginResponse(
                tokenProvider.accessValidity().toSeconds(),
                new LoginResponse.MemberSummary(
                        member.getId(), member.getNickname(), member.getMemberRole(), member.getMemberStatus()));
        return new LoginResult(accessToken, refreshToken, request.keepLogin(), body);
    }

    // 현재 리프레시 토큰을 무효화한다. 쿠키에 토큰이 없거나 이미 폐기됐어도 오류로 보지 않는다.
    @Transactional
    public void logout(Long memberId, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByMemberIdAndRevokedFalse(memberId).stream()
                .filter(stored -> TokenHasher.matches(refreshToken, stored.getTokenHash()))
                .forEach(stored -> stored.revoke());
    }

    // 처리 흐름: 쿠키의 리프레시 토큰 → 서명·만료 검증 → 저장된 해시와 무효화 여부 확인 → 회원 상태 재확인 → 새 액세스 토큰
    // 리프레시 토큰 자체를 교체하는 처리는 2차에서 다룬다. (기능 명세서 3-2)
    @Transactional(readOnly = true)
    public RefreshResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        Long memberId = tokenProvider.parseRefreshToken(refreshToken);

        LocalDateTime now = LocalDateTime.now();
        boolean registered = refreshTokenRepository.findByMemberIdAndRevokedFalse(memberId).stream()
                .anyMatch(stored -> !stored.isExpired(now) && TokenHasher.matches(refreshToken, stored.getTokenHash()));
        if (!registered) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));
        checkLoginAllowed(member);   // 로그인 후에 제한이나 탈퇴가 된 회원은 재발급도 막는다.

        String accessToken = tokenProvider.createAccessToken(member.getId(), member.getMemberRole());
        return new RefreshResult(accessToken, new RefreshResponse(tokenProvider.accessValidity().toSeconds()));
    }

    private boolean isPasswordMatched(Member member, String rawPassword) {
        boolean tooLong = rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
        String hash = (member != null) ? member.getPassword() : dummyPasswordHash;

        // 아이디가 없거나 비밀번호가 너무 길어도 비교를 한 번 수행해 처리 시간을 비슷하게 맞춘다.
        boolean matched = !tooLong && passwordEncoder.matches(rawPassword, hash);
        if (tooLong) {
            passwordEncoder.matches("x", dummyPasswordHash);
        }
        return member != null && matched;
    }

    // 로그인 정지와 탈퇴 회원은 로그인도 재발급도 할 수 없다. 거래 제한 회원은 로그인할 수 있다.
    private void checkLoginAllowed(Member member) {
        MemberStatus status = member.getMemberStatus();
        if (status == MemberStatus.RESTRICTED_LOGIN || status == MemberStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.MEMBER_STATUS_NOT_ALLOWED);
        }
    }
}

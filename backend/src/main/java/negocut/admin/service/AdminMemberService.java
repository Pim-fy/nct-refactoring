package negocut.admin.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.admin.dto.AdminMemberResponses;
import negocut.admin.dto.MemberReleaseRequest;
import negocut.admin.dto.MemberRestrictRequest;
import negocut.admin.dto.ReasonText;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.common.response.PageResult;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRestriction;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.member.repository.MemberRestrictionRepository;
import negocut.trade.dto.TradeSummaryResponse;

// 관리자 회원 관리: 회원 목록과 이용 제한·해제. (기능 명세서 3-10 회원 이용 제한, 2-2 회원 상태)
// 회원의 현재 상태(member.member_status)와 제한 이력(member_restriction)은 같은 트랜잭션에서 함께 바꾼다.
// 같은 회원에 대한 제한·해제는 회원 행을 잠가 한 줄로 세운다. 이미 제한 중인 회원의 교체·해제는 관리자가 화면에서 확인한 현재 제한의
// 식별자와 지금의 현재 제한이 같을 때만 한다(그사이 다른 관리자가 바꿨으면 409 MEMBER_RESTRICTION_CHANGED).
// 관리자 계정은 제한 대상이 아니라 없는 회원과 같이 404로 답하고, 탈퇴 회원은 409다.
@Service
@RequiredArgsConstructor
public class AdminMemberService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 100;
    private static final int MAX_RESTRICTION_YEARS = 10;   // 기간 지정 제한의 만료 시각 상한. 그 너머는 영구 제한으로 건다.
    private static final String REPLACED_REASON = "이용 제한 교체";

    private final MemberRepository memberRepository;
    private final MemberRestrictionRepository restrictionRepository;

    @Transactional(readOnly = true)
    public PageResult<AdminMemberResponses.Item> list(MemberStatus status, String keyword, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE
                || (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Page<Member> members = memberRepository.searchForAdmin(status, likePattern(keyword),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        Map<Long, MemberRestriction> restrictions = members.isEmpty() ? Map.of()
                : restrictionRepository.findByMemberIdInAndReleasedAtIsNull(members.map(Member::getId).getContent()).stream()
                        // 데이터가 어긋나 열린 행이 둘 이상이어도 목록은 읽을 수 있게 가장 최근 행을 현재 제한으로 본다.
                        .collect(Collectors.toMap(restriction -> restriction.getMember().getId(), Function.identity(),
                                (a, b) -> a.getId() > b.getId() ? a : b));
        return PageResult.from(members.map(member -> AdminMemberResponses.Item.of(member, restrictions.get(member.getId()))));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminMemberResponses.Restricted restrict(Long adminId, Long memberId, MemberRestrictRequest request) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        LocalDateTime expiresAt = validateRestriction(request, now);

        Member member = requireRestrictable(memberId);
        List<MemberRestriction> open = restrictionRepository.findByMemberIdAndReleasedAtIsNullOrderByIdDesc(memberId);
        boolean restricted = member.getMemberStatus() != MemberStatus.NORMAL;
        Long expectedId = request.expectedRestrictionId();
        if (restricted && expectedId == null && !open.isEmpty()) {
            throw new BusinessException(ErrorCode.MEMBER_ALREADY_RESTRICTED);   // 현재 제한을 확인하지 않고 건 요청이다.
        }
        requireSameRestriction(open, expectedId);
        Member admin = memberRepository.getReferenceById(adminId);
        // 제한 중이면 기존 이력을 해제로 닫고 새 이력을 추가한다. 같은 수준이어도 기간·사유를 바꾸는 교체로 본다.
        open.forEach(current -> current.release(admin, REPLACED_REASON, now));
        MemberRestriction restriction = restrictionRepository.saveAndFlush(MemberRestriction.start(member,
                request.restrictionLevel(), request.reason(), request.isPermanent(), now, expiresAt, admin));
        member.restrict(request.restrictionLevel());
        return new AdminMemberResponses.Restricted(memberId, member.getMemberStatus(), restriction.getId(),
                TradeSummaryResponse.toOffset(now), TradeSummaryResponse.toOffset(restriction.getExpiresAt()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminMemberResponses.Released release(Long adminId, Long memberId, MemberReleaseRequest request) {
        if (!ReasonText.isMeaningful(request.reason())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Member member = requireRestrictable(memberId);
        if (member.getMemberStatus() == MemberStatus.NORMAL) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_RESTRICTED);
        }
        List<MemberRestriction> open = restrictionRepository.findByMemberIdAndReleasedAtIsNullOrderByIdDesc(memberId);
        requireSameRestriction(open, request.restrictionId());
        LocalDateTime now = LocalDateTime.now(ZONE);
        Member admin = memberRepository.getReferenceById(adminId);
        open.forEach(current -> current.release(admin, request.reason(), now));
        member.releaseRestriction();
        return new AdminMemberResponses.Released(memberId, member.getMemberStatus(), TradeSummaryResponse.toOffset(now));
    }

    // 제한 요청의 입력을 확인하고 만료 시각(영구 제한이면 null)을 돌려준다.
    private LocalDateTime validateRestriction(MemberRestrictRequest request, LocalDateTime now) {
        MemberStatus level = request.restrictionLevel();
        if (level != MemberStatus.RESTRICTED_TRADE && level != MemberStatus.RESTRICTED_LOGIN) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (!ReasonText.isMeaningful(request.reason())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (request.isPermanent()) {
            if (request.expiresAt() != null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);   // 영구 제한에는 만료 시각을 보내지 않는다.
            }
            return null;
        }
        if (request.expiresAt() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        LocalDateTime expiresAt = request.expiresAt().atZoneSameInstant(ZONE).toLocalDateTime();
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plusYears(MAX_RESTRICTION_YEARS))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return expiresAt;
    }

    // 관리자가 확인한 제한(expectedId)이 지금의 현재 제한과 같은지 본다. 제한 중이 아닌 회원에는 확인할 제한이 없어야 하고,
    // 제한 중이면 가장 최근 열린 행의 식별자와 같아야 한다. 다르면 그사이 다른 관리자가 바꾼 것이다.
    private void requireSameRestriction(List<MemberRestriction> open, Long expectedId) {
        Long currentId = open.isEmpty() ? null : open.get(0).getId();
        if (currentId == null ? expectedId != null : !currentId.equals(expectedId)) {
            throw new BusinessException(ErrorCode.MEMBER_RESTRICTION_CHANGED);
        }
    }

    // 회원 행을 잠그고 읽는다. 없거나 관리자 계정이면 404, 탈퇴 회원이면 409다.
    private Member requireRestrictable(Long memberId) {
        Member member = memberRepository.findByIdForUpdate(memberId).orElse(null);
        if (member == null || member.getMemberRole() == MemberRole.ADMIN) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if (member.getMemberStatus() == MemberStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.MEMBER_ALREADY_WITHDRAWN);
        }
        return member;
    }

    // 검색어를 포함 검색 패턴으로 바꾼다. 앞뒤 공백을 지우고 LIKE의 특수 문자(%, _)와 이스케이프 문자(!)는 글자 그대로 찾는다.
    private String likePattern(String keyword) {
        String trimmed = keyword == null ? "" : keyword.strip();
        return "%" + trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}

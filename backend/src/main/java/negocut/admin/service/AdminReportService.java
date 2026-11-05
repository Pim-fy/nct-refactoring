package negocut.admin.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import negocut.admin.dto.AdminReportResponses;
import negocut.admin.dto.AuctionStopRequest;
import negocut.admin.dto.MemberRestrictRequest;
import negocut.admin.dto.ReasonText;
import negocut.admin.dto.ReportProcessRequest;
import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionStatus;
import negocut.auction.repository.AuctionRepository;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.common.response.PageResult;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRestriction;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.member.repository.MemberRestrictionRepository;
import negocut.report.entity.Report;
import negocut.report.entity.ReportActionType;
import negocut.report.entity.ReportStatus;
import negocut.report.entity.ReportTargetType;
import negocut.report.repository.ReportRepository;
import negocut.report.service.ReportService;

// 관리자 신고 관리: 신고 목록과 신고 처리. (기능 명세서 3-10 신고 처리)
// 처리 결과는 경매 강제 종료·숨김, 회원 이용 제한, 반려다. 경매 중단과 이용 제한은 같은 요청에서 함께 수행하며 기존 서비스를 그대로 쓴다.
// 같은 신고의 동시 처리와 낡은 화면의 처리는 신고 행을 가장 먼저 잠근 뒤 접수 상태를 다시 확인해 막는다.
// 먼저 처리된 신고는 409 REPORT_ALREADY_PROCESSED이고, 반려한 신고도 다시 처리하지 않는다.
// 잠금 순서는 신고 → 경매 또는 회원 → 최고 입찰자 잔액이다. 경매·회원 처리는 신고 행을 잠그지 않으므로 서로 순환하지 않는다.
// 조치 대상이 이미 그 상태이면(이미 중단된 경매, 이미 제한된 회원) 조치는 다시 하지 않고 신고만 처리 완료로 남긴다.
// 이용 제한의 대상 회원은 신고 대상 유형으로 서버가 정한다. 회원 신고는 그 회원, 경매 신고는 판매자, 거래 신고는 신고자의 상대 당사자다.
// 경매 중단은 경매 신고에만 쓸 수 있고, 이미 낙찰·유찰된 경매는 중단할 수 없어 반려나 회원 제한으로만 처리한다.
@Service
@RequiredArgsConstructor
public class AdminReportService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final int MAX_PAGE_SIZE = 100;

    private final ReportRepository reportRepository;
    private final MemberRepository memberRepository;
    private final MemberRestrictionRepository restrictionRepository;
    private final AuctionRepository auctionRepository;
    private final AdminAuctionService adminAuctionService;
    private final AdminMemberService adminMemberService;
    private final ReportService reportService;
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public PageResult<AdminReportResponses.Item> list(ReportStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Page<Report> reports = reportRepository.searchForAdmin(status, PageRequest.of(page, size));
        Map<Long, String> labels = reportService.targetLabelsByReportId(reports.getContent());
        return PageResult.from(reports.map(report -> AdminReportResponses.Item.of(report, labels.get(report.getId()))));
    }

    // READ_COMMITTED로 실행해, 잠금을 기다린 사이에 커밋된 경매·회원·잔액을 읽는다. (경매 중단·이용 제한과 같은 이유)
    // 안쪽의 경매 중단·이용 제한이 이 트랜잭션에 합류하므로 신고 처리와 조치는 함께 성공하거나 함께 취소된다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminReportResponses.Processed process(Long adminId, Long reportId, ReportProcessRequest request) {
        validateShape(request);
        Report report = reportRepository.findByIdForUpdate(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        if (report.getReportStatus() != ReportStatus.RECEIVED) {
            throw new BusinessException(ErrorCode.REPORT_ALREADY_PROCESSED);
        }

        LocalDateTime now = LocalDateTime.now(ZONE);
        Member admin = memberRepository.getReferenceById(adminId);
        AdminReportResponses.AppliedAction applied = null;
        switch (request.actionType()) {
            case AUCTION_STOPPED -> {
                if (report.getTargetType() != ReportTargetType.AUCTION) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);   // 경매 중단은 경매 신고에만 쓴다.
                }
                applied = stopAuction(adminId, report.getTargetId(), request.actionReason());
                report.process(ReportActionType.AUCTION_STOPPED, request.actionReason(), admin, now);
            }
            case MEMBER_RESTRICTED -> {
                applied = restrictMember(adminId, targetMemberOf(report), request);
                report.process(ReportActionType.MEMBER_RESTRICTED, request.actionReason(), admin, now);
            }
            case REJECTED -> report.reject(request.actionReason(), admin, now);
        }
        return new AdminReportResponses.Processed(report.getId(), report.getReportStatus(), report.getActionType(), applied);
    }

    // 요청 모양을 확인한다. 사유는 읽을 수 있는 글자가 있어야 하고, 제한 정보는 이용 제한으로 처리할 때만 보낸다.
    private void validateShape(ReportProcessRequest request) {
        if (!ReasonText.isMeaningful(request.actionReason())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        boolean restrict = request.actionType() == ReportActionType.MEMBER_RESTRICTED;
        if (restrict != (request.restriction() != null)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    // 경매 행을 잠그고 상태를 본다. 이미 중단됐으면 조치 없이 신고만 처리하고, 등록·진행 중이면 중단한다. 낙찰·유찰이면 409다.
    private AdminReportResponses.AuctionAction stopAuction(Long adminId, Long auctionId, String reason) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));
        if (auction.getAuctionStatus() == AuctionStatus.STOPPED) {
            return new AdminReportResponses.AuctionAction(auctionId, AuctionStatus.STOPPED, true);
        }
        adminAuctionService.stop(adminId, auctionId, new AuctionStopRequest(reason));   // 낙찰·유찰이면 AUCTION_STATUS_INVALID
        return new AdminReportResponses.AuctionAction(auctionId, auction.getAuctionStatus(), false);
    }

    // 회원 행을 잠그고 상태를 본다. 이미 제한 중이면 조치 없이 신고만 처리하고, 정상이면 제한을 건다. 탈퇴 회원은 409다.
    private AdminReportResponses.MemberAction restrictMember(Long adminId, Long memberId, ReportProcessRequest request) {
        ReportProcessRequest.Restriction restriction = request.restriction();
        MemberRestrictRequest restrict = new MemberRestrictRequest(restriction.restrictionLevel(), restriction.isPermanent(),
                restriction.expiresAt(), request.actionReason(), null);
        adminMemberService.validateRestriction(restrict, LocalDateTime.now(ZONE));   // 이미 제한된 회원에게도 같은 입력 검사를 한다.

        Member member = memberRepository.findByIdForUpdate(memberId).orElse(null);
        if (member == null || member.getMemberRole() == MemberRole.ADMIN) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if (member.getMemberStatus() == MemberStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.MEMBER_ALREADY_WITHDRAWN);
        }
        if (member.getMemberStatus() != MemberStatus.NORMAL) {
            List<MemberRestriction> open = restrictionRepository.findByMemberIdAndReleasedAtIsNullOrderByIdDesc(memberId);
            return new AdminReportResponses.MemberAction(memberId, member.getMemberStatus(),
                    open.isEmpty() ? null : open.get(0).getId(), true);
        }
        var restricted = adminMemberService.restrict(adminId, memberId, restrict);
        return new AdminReportResponses.MemberAction(memberId, restricted.memberStatus(), restricted.restrictionId(), false);
    }

    // 이용 제한을 걸 회원을 신고 대상 유형으로 정한다. 대상이 없으면 신고 대상을 찾을 수 없는 것과 같이 404다.
    private Long targetMemberOf(Report report) {
        Long targetId = report.getTargetId();
        Long memberId = switch (report.getTargetType()) {
            case MEMBER -> targetId;
            case AUCTION -> entityManager.createQuery("select a.seller.id from Auction a where a.id = :id", Long.class)
                    .setParameter("id", targetId).getResultStream().findFirst().orElse(null);
            case TRADE -> {
                List<Object[]> rows = entityManager.createQuery(
                        "select t.seller.id, t.buyer.id from Trade t where t.id = :id", Object[].class)
                        .setParameter("id", targetId).getResultList();
                // 신고자가 판매자이면 낙찰자를, 아니면 판매자를 제한한다.
                yield rows.isEmpty() ? null : (report.getReporter().getId().equals(rows.get(0)[0]) ? (Long) rows.get(0)[1] : (Long) rows.get(0)[0]);
            }
        };
        if (memberId == null) {
            throw new BusinessException(ErrorCode.REPORT_TARGET_NOT_FOUND);
        }
        return memberId;
    }
}

package negocut.report.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import negocut.auction.entity.AuctionStatus;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.common.response.PageResult;
import negocut.common.util.Nicknames;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.report.dto.ReportRequest;
import negocut.report.dto.ReportResponses;
import negocut.report.entity.Report;
import negocut.report.entity.ReportReason;
import negocut.report.entity.ReportTargetType;
import negocut.report.repository.ReportRepository;

// 신고 접수와 내 신고 내역. (기능 명세서 3-9 신고)
// 대상은 경매·회원·거래다. 본인 경매와 본인 자신은 신고할 수 없고, 거래는 당사자(판매자·낙찰자)만 신고할 수 있다.
// 신고는 운영 알림이라 거래 제한 회원도 접수할 수 있다. (컨트롤러의 @AllowTradeRestricted)
// 같은 회원이 같은 대상을 처리 전까지 한 건만 신고할 수 있으며, 동시에 들어온 접수는 active_key의 유니크 제약이 막는다.
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String UNKNOWN_LABEL = "확인할 수 없는 대상";

    private final ReportRepository reportRepository;
    private final MemberRepository memberRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReportResponses.Received receive(Long memberId, ReportRequest request) {
        if (request.reportReason() == ReportReason.INAPPROPRIATE_OR_ETC && request.detail() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);   // 부적절 행위·기타는 상세 내용이 필수다.
        }
        requireReportableTarget(memberId, request.targetType(), request.targetId());

        String activeKey = Report.activeKeyOf(memberId, request.targetType(), request.targetId());
        if (reportRepository.existsByActiveKey(activeKey)) {
            throw new BusinessException(ErrorCode.REPORT_ALREADY_SUBMITTED);
        }
        Report report = Report.receive(memberRepository.getReferenceById(memberId), request.targetType(), request.targetId(),
                request.reportReason(), request.detail());
        try {
            report = reportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.REPORT_ALREADY_SUBMITTED);   // 같은 신고가 동시에 들어왔다.
        }
        return new ReportResponses.Received(report.getId(), report.getReportStatus());
    }

    // 내 신고 내역. 본인이 접수한 신고만, 최근 신고가 먼저다. 처리 사유와 처리자는 담지 않는다.
    @Transactional(readOnly = true)
    public PageResult<ReportResponses.Mine> getMyReports(Long memberId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Page<Report> reports = reportRepository.findByReporterId(memberId, PageRequest.of(page, size));
        Map<ReportTargetType, Map<Long, String>> labels = labelsOf(reports.getContent());
        return PageResult.from(reports.map(report -> ReportResponses.Mine.of(report,
                labels.get(report.getTargetType()).getOrDefault(report.getTargetId(), UNKNOWN_LABEL))));
    }

    // 신고할 수 있는 대상인지 확인한다. 없거나 신고 대상이 아니면 404, 본인 경매·본인 자신이면 403이다.
    private void requireReportableTarget(Long memberId, ReportTargetType type, Long targetId) {
        switch (type) {
            case AUCTION -> {
                List<Object[]> rows = entityManager.createQuery(
                        "select a.seller.id, a.auctionStatus from Auction a where a.id = :id", Object[].class)
                        .setParameter("id", targetId).getResultList();
                // 중단된 경매는 이미 노출하지 않으므로 신고 대상에서 뺀다.
                if (rows.isEmpty() || rows.get(0)[1] == AuctionStatus.STOPPED) {
                    throw new BusinessException(ErrorCode.REPORT_TARGET_NOT_FOUND);
                }
                if (memberId.equals(rows.get(0)[0])) {
                    throw new BusinessException(ErrorCode.REPORT_SELF_NOT_ALLOWED);
                }
            }
            case MEMBER -> {
                List<Object[]> rows = entityManager.createQuery(
                        "select m.memberRole, m.memberStatus from Member m where m.id = :id", Object[].class)
                        .setParameter("id", targetId).getResultList();
                // 탈퇴한 회원과 관리자는 이용 제한 대상이 아니므로 신고 대상에서 뺀다.
                if (rows.isEmpty() || rows.get(0)[0] == MemberRole.ADMIN || rows.get(0)[1] == MemberStatus.WITHDRAWN) {
                    throw new BusinessException(ErrorCode.REPORT_TARGET_NOT_FOUND);
                }
                if (memberId.equals(targetId)) {
                    throw new BusinessException(ErrorCode.REPORT_SELF_NOT_ALLOWED);
                }
            }
            case TRADE -> {
                // 거래는 당사자만 신고할 수 있다. 비당사자는 거래가 있는지 알 수 없도록 없는 거래와 같이 404로 답한다.
                Long count = entityManager.createQuery(
                        "select count(t) from Trade t where t.id = :id and (t.seller.id = :memberId or t.buyer.id = :memberId)", Long.class)
                        .setParameter("id", targetId).setParameter("memberId", memberId).getSingleResult();
                if (count == 0) {
                    throw new BusinessException(ErrorCode.REPORT_TARGET_NOT_FOUND);
                }
            }
        }
    }

    // 목록의 대상 표시 문구를 유형별로 한 번에 읽는다. 경매·거래는 상품명, 회원은 가린 닉네임이다.
    private Map<ReportTargetType, Map<Long, String>> labelsOf(List<Report> reports) {
        Map<ReportTargetType, Map<Long, String>> labels = new HashMap<>();
        for (ReportTargetType type : ReportTargetType.values()) {
            Set<Long> ids = reports.stream().filter(report -> report.getTargetType() == type)
                    .map(Report::getTargetId).collect(Collectors.toSet());
            labels.put(type, ids.isEmpty() ? Map.of() : labelsOf(type, ids));
        }
        return labels;
    }

    private Map<Long, String> labelsOf(ReportTargetType type, Set<Long> ids) {
        String query = switch (type) {
            case AUCTION -> "select a.id, a.product.name from Auction a where a.id in :ids";
            case MEMBER -> "select m.id, m.nickname from Member m where m.id in :ids";
            case TRADE -> "select t.id, t.auctionResult.auction.product.name from Trade t where t.id in :ids";
        };
        Map<Long, String> labels = new HashMap<>();
        for (Object[] row : entityManager.createQuery(query, Object[].class).setParameter("ids", ids).getResultList()) {
            String label = (String) row[1];
            labels.put((Long) row[0], type == ReportTargetType.MEMBER ? Nicknames.mask(label) : label);
        }
        return labels;
    }
}

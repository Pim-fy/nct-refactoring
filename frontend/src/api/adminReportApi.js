import api from './axios';
import { unwrap } from './apiResponse';

// 관리자 신고 목록(최근 신고가 먼저). (API 명세서 7장)
// params: { status?, page, size } — status는 RECEIVED|PROCESSED|REJECTED, 없으면 전체
// 응답: { content: [{ reportId, targetType, targetId, targetLabel, reporterNickname, reportReason, detail, reportStatus,
//                   actionType(처리 전 null), actionReason(처리 전 null), createdAt, processedAt(처리 전 null) }], page, size, totalElements, totalPages }
export async function fetchAdminReports(params = {}) {
  return unwrap(await api.get('/admin/reports', { params }));
}

// 신고 처리. 경매 중단·회원 이용 제한은 같은 요청에서 함께 수행하고, 이미 처리·반려된 신고는 409 REPORT_ALREADY_PROCESSED다.
// payload: { actionType: 'AUCTION_STOPPED'(경매 신고만)|'MEMBER_RESTRICTED'|'REJECTED', actionReason(500자 이하),
//           restriction?: { restrictionLevel, isPermanent, expiresAt? }(MEMBER_RESTRICTED일 때만) }
// 응답: { reportId, reportStatus, actionType, appliedAction: { auctionId, auctionStatus, isAlreadyApplied }
//                                                          | { memberId, memberStatus, restrictionId, isAlreadyApplied } | null(반려) }
export async function processReport(reportId, payload) {
  return unwrap(await api.post(`/admin/reports/${reportId}/process`, payload));
}

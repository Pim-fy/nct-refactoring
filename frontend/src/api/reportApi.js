import api from './axios';
import { unwrap } from './apiResponse';

// 신고 접수. 거래 제한 회원도 할 수 있다. 본인 경매·본인 자신은 403, 없는 대상은 404, 처리 전 같은 대상 중복은 409다. (API 명세서 6장)
// payload: { targetType: 'AUCTION'|'MEMBER'|'TRADE', targetId, reportReason, detail? } — detail은 INAPPROPRIATE_OR_ETC일 때 필수(500자 이하)
// 응답: { reportId, reportStatus }
export async function submitReport(payload) {
  return unwrap(await api.post('/reports', payload));
}

// 내 신고 내역(최근 신고가 먼저). 처리 사유와 처리자는 내려오지 않는다.
// 응답: { content: [{ reportId, targetType, targetLabel, reportReason, reportStatus, actionType, createdAt, processedAt }], page, size, totalElements, totalPages }
export async function fetchMyReports(params = {}) {
  return unwrap(await api.get('/members/me/reports', { params }));
}

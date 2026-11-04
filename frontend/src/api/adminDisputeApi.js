import api from './axios';
import { unwrap } from './apiResponse';

// 관리자 분쟁 목록(최근 접수가 먼저). (API 명세서 7장)
// params: { status?: 'OPEN'|'JUDGED', page, size }
// 응답: { content: [{ disputeId, tradeId, productName, tradePrice, reporterNickname, reporterRole('SELLER'|'BUYER'), reason, previousTradeStatus,
//                   disputeStatus, judgment(판정 전에는 null, 보류 유지는 OPEN인 채 HOLD), createdAt }], page, size, totalElements, totalPages }
export async function fetchAdminDisputes(params = {}) {
  return unwrap(await api.get('/admin/disputes', { params }));
}

// 분쟁 처리를 위한 거래 상세. 닉네임을 가리지 않고 배송 정보를 모두 담으며, 분쟁은 모든 분쟁을 최근 것부터 담는다.
// 응답: { tradeId, tradeStatus, tradePrice, sellerNickname, buyerNickname, auction, schedule, delivery, cancelRequest,
//        disputes: [{ disputeId, disputeStatus, reporterRole, reporterNickname, reason, previousTradeStatus, createdAt, judgment, judgmentReason, judgedAt,
//                    resumeDue: { dueType('SHIP'|'CONFIRM'), currentDueAt, resumedDueAt, adjusted } | null }] }
// resumeDue는 처리 중인 분쟁에만 있고, 지금 거래 진행으로 판정하면 정해질 기한이다.
export async function fetchAdminTrade(tradeId) {
  return unwrap(await api.get(`/admin/trades/${tradeId}`));
}

// 분쟁 판정. payload: { judgment: 'REFUND'|'PAYOUT'|'RESUME'|'HOLD', judgmentReason(500자 이하) }
// 이미 판정이 끝난 분쟁은 409 DISPUTE_ALREADY_CLOSED, 발송이 등록되지 않은 거래의 지급은 409 DISPUTE_JUDGMENT_NOT_ALLOWED다.
// 응답: { disputeId, disputeStatus, judgment, tradeId, tradeStatus, judgedAt, dueType?, dueAt?, isDueAdjusted? } — 기한은 거래 진행 판정에만 있다.
export async function judgeDispute(disputeId, payload) {
  return unwrap(await api.post(`/admin/disputes/${disputeId}/judge`, payload));
}

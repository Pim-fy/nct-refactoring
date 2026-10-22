import api from './axios';
import { unwrap } from './apiResponse';

// 내 거래 목록(최근 거래가 먼저). params: { role?: 'SELLER'|'BUYER', status?, page?, size? } (API 명세서 5-2)
// 응답: { content: [{ tradeId, auctionId, productName, thumbnailUrl, tradePrice, tradeStatus, myRole, isUnfulfilled, dueAt, createdAt }], page, size, totalElements, totalPages }
// dueAt은 현재 상태에 해당하는 기한이다(준비면 발송 기한, 진행이면 구매 확정 기한).
export async function fetchMyTrades(params = {}) {
  const cleaned = Object.fromEntries(Object.entries(params).filter(([, value]) => value !== '' && value !== null && value !== undefined));
  return unwrap(await api.get('/members/me/trades', { params: cleaned }));
}

// 거래 상세. 당사자(판매자·낙찰자)만 볼 수 있다. availableActions로 보여 줄 버튼을 정한다.
// 응답: { tradeId, tradeStatus, myRole, tradePrice, counterparty, auction, schedule, delivery, cancelRequest, dispute, availableActions }
export async function fetchTrade(tradeId) {
  return unwrap(await api.get(`/trades/${tradeId}`));
}

// 배송지 입력(낙찰자). 발송이 등록되기 전(준비 상태)까지 다시 입력해 고칠 수 있다.
// payload: { receiverName, receiverPhone, zipCode, address, addressDetail?, deliveryMessage? }
export async function saveDelivery(tradeId, payload) {
  return unwrap(await api.put(`/trades/${tradeId}/delivery`, payload));
}

// 발송 등록(판매자). 배송지가 입력된 준비 상태에서만 할 수 있다. payload: { courierName, trackingNumber }
export async function registerShipment(tradeId, payload) {
  return unwrap(await api.post(`/trades/${tradeId}/shipment`, payload));
}

// 구매 확정(낙찰자). 정상이면 포인트가 지급되고 tradeStatus가 COMPLETED로 돌아온다. 지급이 실패하면 CONFIRMED에 멈춘다.
export async function confirmPurchase(tradeId) {
  return unwrap(await api.post(`/trades/${tradeId}/confirm`));
}

// 거래 취소 요청. 판매자·낙찰자 누구나 준비·진행 상태에서 사유(500자 이하)와 함께 요청한다. 처리 중인 요청이 있거나 3회를 넘으면 409다.
// 응답: { requestId, requestStatus }
export async function requestCancel(tradeId, reason) {
  return unwrap(await api.post(`/trades/${tradeId}/cancel-requests`, { reason }));
}

// 상대방이 요청한 취소를 수락한다. 거래가 CANCELED가 되고 낙찰자의 예약 포인트가 반환된다.
// 응답: { tradeId, tradeStatus, requestStatus, point } — point는 낙찰자에게만 있고 판매자 응답에서는 null이다.
export async function acceptCancel(tradeId, requestId) {
  return unwrap(await api.post(`/trades/${tradeId}/cancel-requests/${requestId}/accept`));
}

// 상대방이 요청한 취소를 거절한다. 응답: { tradeId, tradeStatus, requestStatus }
export async function rejectCancel(tradeId, requestId) {
  return unwrap(await api.post(`/trades/${tradeId}/cancel-requests/${requestId}/reject`));
}

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

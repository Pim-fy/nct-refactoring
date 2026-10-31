import api from './axios';
import { unwrap } from './apiResponse';

// 관리자 경매 목록(중단 포함, 최신 등록순). (API 명세서 7장)
// params: { status?, keyword?(상품명), page, size } — status는 REGISTERED|IN_PROGRESS|SOLD|FAILED|STOPPED
// 응답: { content: [{ auctionId, productName, sellerNickname, currentPrice(입찰이 없으면 null), bidCount, endAt, auctionStatus, stoppedReason(중단된 경매만) }], page, size, totalElements, totalPages }
export async function fetchAdminAuctions(params = {}) {
  return unwrap(await api.get('/admin/auctions', { params }));
}

// 경매 강제 종료·숨김. 등록·진행 중인 경매만 되고 아니면 409 AUCTION_STATUS_INVALID다. 남은 예약 포인트는 함께 해제된다.
// payload: { stoppedReason(500자 이하) } 응답: { auctionId, auctionStatus, stoppedAt, releasedReservationCount }
export async function stopAuction(auctionId, payload) {
  return unwrap(await api.post(`/admin/auctions/${auctionId}/stop`, payload));
}

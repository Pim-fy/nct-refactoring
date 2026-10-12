import api from './axios';
import { unwrap } from './apiResponse';

// 경매 등록. 응답: { auctionId } (API 명세서 3-1)
// payload: { product: { name, categoryId, description, conditionNote, imageIds }, auction: { startPrice, bidUnit, endAt } }
// 등록한 경매는 수정·삭제할 수 없다. 오류는 AUCTION_INPUT_INVALID(필드별 사유 details), AUCTION_END_TIME_INVALID,
// AUCTION_IMAGE_REQUIRED다.
export async function registerAuction(payload) {
  return unwrap(await api.post('/auctions', payload));
}

// 내가 등록한 경매 목록(최신 등록순). 응답: { content: [...], page, size, totalElements, totalPages }
export async function fetchMyAuctions({ page = 0, size = 20 } = {}) {
  return unwrap(await api.get('/members/me/auctions', { params: { page, size } }));
}

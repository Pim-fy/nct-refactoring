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

// 공개 경매 목록. params: { category?, keyword?, sort?, page?, size? } (API 명세서 3-1)
// sort: endAt(마감 임박순, 기본), createdAt(최신순), price(현재가 낮은순), bidCount(입찰 많은순). 중단된 경매는 나오지 않는다.
// 응답: 내 경매 목록과 같은 형식
export async function fetchAuctions(params = {}) {
  const cleaned = Object.fromEntries(Object.entries(params).filter(([, value]) => value !== '' && value !== null && value !== undefined));
  return unwrap(await api.get('/auctions', { params: cleaned }));
}

// 경매 상세(공개). 로그인했으면 isMine과 canBid가 로그인 정보로 정해진다. 없는 경매와 열람할 수 없는 중단 경매는 404 AUCTION_NOT_FOUND다.
// 응답: { auctionId, auctionStatus, seller, product, terms, bidding, result, stoppedReason } (API 명세서 3-1)
export async function fetchAuction(auctionId) {
  return unwrap(await api.get(`/auctions/${auctionId}`));
}

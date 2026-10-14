import api from './axios';
import { unwrap } from './apiResponse';

// 입찰. 응답: { bidId, auctionId, bidPrice, currentPrice, isTopBidder, minimumBidPrice, point } (API 명세서 4-1)
// 갱신된 포인트(point)가 함께 와서 포인트를 다시 조회하지 않아도 된다.
// 금액은 입찰 단위의 배수이고 최소 입찰 금액 이상인 정수여야 한다. 오류는 AUCTION_NOT_IN_PROGRESS(409), BID_SELF_AUCTION_NOT_ALLOWED(403),
// BID_AMOUNT_TOO_LOW(400), BID_UNIT_MISMATCH(400), POINT_NOT_ENOUGH(409), 거래 제한 회원은 MEMBER_TRADE_RESTRICTED(403)다.
export async function placeBid(auctionId, bidPrice) {
  return unwrap(await api.post(`/auctions/${auctionId}/bids`, { bidPrice }));
}

// 경매의 입찰 내역(공개). 높은 금액이 먼저다. 입찰자는 일부를 가리고 내 입찰은 isMine이 true다.
// 응답: { content: [{ bidId, bidPrice, bidAt, maskedBidderNickname, isMine }], page, size, totalElements, totalPages }
export async function fetchBids(auctionId, { page = 0, size = 10 } = {}) {
  return unwrap(await api.get(`/auctions/${auctionId}/bids`, { params: { page, size } }));
}

// 내 입찰 목록. 경매마다 한 줄이고 내가 마지막으로 입찰한 경매가 먼저다.
// 응답: { content: [{ auctionId, productName, thumbnailUrl, myBidPrice, currentPrice, isTopBidder, isReserved, endAt, remainingSeconds, auctionStatus }], ... }
export async function fetchMyBids({ page = 0, size = 20 } = {}) {
  return unwrap(await api.get('/members/me/bids', { params: { page, size } }));
}

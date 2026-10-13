import api from './axios';
import { unwrap } from './apiResponse';

// 내 포인트 조회. 응답: { totalPoint, reservedPoint, availablePoint } (API 명세서 4-1)
// availablePoint는 보유에서 예약을 뺀 값이며 서버가 계산한다.
export async function fetchMyPoints() {
  return unwrap(await api.get('/members/me/points'));
}

// 포인트 충전. 금액은 1,000 포인트 단위, 1회 1,000~1,000,000이고 충전 후 보유 포인트는 10,000,000을 넘을 수 없다.
// 응답은 조회와 같은 형식이다. 오류는 POINT_CHARGE_AMOUNT_INVALID(400), POINT_BALANCE_LIMIT_EXCEEDED(409),
// 거래 제한 회원은 MEMBER_TRADE_RESTRICTED(403)다.
export async function chargePoints(amount) {
  return unwrap(await api.post('/members/me/points/charge', { amount }));
}

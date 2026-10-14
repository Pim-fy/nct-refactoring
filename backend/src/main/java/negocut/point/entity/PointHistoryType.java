package negocut.point.entity;

// 포인트 변동 이력의 종류. (DB 설계서 2-5)
public enum PointHistoryType {
    CHARGE,    // 충전
    RESERVE,   // 예약
    RELEASE,   // 해제
    REFUND,    // 반환
    PAYOUT     // 지급
}

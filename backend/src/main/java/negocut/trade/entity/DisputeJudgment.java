package negocut.trade.entity;

// 관리자의 분쟁 판정. (기능 명세서 3-10 분쟁 판정)
public enum DisputeJudgment {
    REFUND,   // 반환: 낙찰자에게 예약 포인트를 반환하고 거래를 취소로 바꿈
    PAYOUT,   // 지급: 판매자에게 포인트를 지급하고 거래를 완료로 바꿈
    RESUME,   // 거래 진행: 분쟁을 닫고 거래를 접수 직전 상태로 되돌림
    HOLD      // 보류 유지: 거래를 분쟁 상태로 두고 추가 확인을 기다림
}

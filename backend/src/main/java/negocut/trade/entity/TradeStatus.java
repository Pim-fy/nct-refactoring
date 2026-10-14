package negocut.trade.entity;

// 거래 상태. (기능 명세서 2-2 거래 상태, DB 설계서 2-6)
// 낙찰로 거래가 만들어지면 READY로 시작하고, 이후 상태 변경은 거래 구현에서 추가한다.
public enum TradeStatus {
    READY,         // 준비: 낙찰자의 배송지 입력을 기다림
    IN_PROGRESS,   // 진행: 판매자가 발송하고 운송장을 등록함
    CONFIRMED,     // 구매 확정: 낙찰자가 수령을 확정함
    COMPLETED,     // 완료: 판매자에게 포인트를 지급하고 거래가 끝남
    CANCELED,      // 취소: 양쪽 합의로 취소하고 낙찰자에게 포인트를 반환함
    DISPUTED       // 분쟁: 관리자 판정을 기다림
}

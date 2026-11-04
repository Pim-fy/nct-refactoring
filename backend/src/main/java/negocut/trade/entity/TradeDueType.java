package negocut.trade.entity;

// 거래의 기한 종류. 분쟁 판정으로 거래를 되돌릴 때 어느 기한을 새로 정하는지 나타낸다. (기능 명세서 3-10 분쟁 판정)
public enum TradeDueType {
    SHIP,      // 발송 기한: 접수 직전 상태가 준비였다
    CONFIRM    // 구매 확정 기한: 접수 직전 상태가 진행이었다
}

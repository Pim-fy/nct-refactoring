package negocut.trade.entity;

// 분쟁 상태. (기능 명세서 2-2, DB 설계서 2-6)
public enum DisputeStatus {
    OPEN,     // 처리 중: 관리자 판정을 기다림
    JUDGED    // 판정 완료: 거래 진행으로 판정한 분쟁도 닫는다
}

package negocut.report.entity;

// 신고 사유. 부적절 행위·기타는 상세 내용이 필수다. (기능 명세서 3-9)
public enum ReportReason {
    FALSE_INFO,             // 허위·과장 정보
    PROHIBITED_ITEM,        // 금지 물품
    TRADE_MISCONDUCT,       // 거래 미이행·불성실
    INAPPROPRIATE_OR_ETC    // 부적절 행위·기타
}

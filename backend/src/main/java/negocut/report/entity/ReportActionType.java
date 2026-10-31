package negocut.report.entity;

// 신고 처리 결과. (기능 명세서 3-10 신고 처리)
public enum ReportActionType {
    AUCTION_STOPPED,     // 경매 강제 종료·숨김
    MEMBER_RESTRICTED,   // 회원 이용 제한
    REJECTED             // 반려
}

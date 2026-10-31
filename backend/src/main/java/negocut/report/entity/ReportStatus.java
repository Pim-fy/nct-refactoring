package negocut.report.entity;

// 신고 상태. (기능 명세서 3-9, DB 설계서 2-7)
public enum ReportStatus {
    RECEIVED,    // 접수
    PROCESSED,   // 처리 완료
    REJECTED     // 반려
}

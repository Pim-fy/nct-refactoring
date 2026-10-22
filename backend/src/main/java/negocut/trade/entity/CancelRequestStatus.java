package negocut.trade.entity;

// 거래 취소 요청 상태. (기능 명세서 2-2, DB 설계서 2-6)
public enum CancelRequestStatus {
    REQUESTED,   // 요청: 상대방의 응답을 기다림(처리 중)
    ACCEPTED,    // 수락: 거래가 취소됨
    REJECTED,    // 거절: 거래는 원래 상태로 남음
    CLOSED       // 종결: 거래가 다른 상태로 바뀌어 요청이 의미를 잃어 시스템이 닫음
}

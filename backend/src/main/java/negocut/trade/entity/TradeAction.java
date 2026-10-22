package negocut.trade.entity;

// 거래 상세에서 보는 사람이 지금 할 수 있는 행동. 화면은 이 목록으로 버튼을 만든다. (API 명세서 5-1)
// 분쟁 접수는 분쟁 기능을 구현할 때 추가한다.
public enum TradeAction {
    INPUT_DELIVERY,       // 배송지 입력: 낙찰자, 준비
    REGISTER_SHIPMENT,    // 발송 등록: 판매자, 준비, 배송지 입력 완료
    CONFIRM_PURCHASE,     // 구매 확정: 낙찰자, 진행
    REQUEST_CANCEL,       // 거래 취소 요청: 양쪽, 준비·진행, 처리 중인 취소 요청 없음, 요청 횟수가 남음
    ACCEPT_CANCEL,        // 취소 요청 수락: 상대방이 요청한 취소가 처리 중, 준비·진행
    REJECT_CANCEL         // 취소 요청 거절: 상대방이 요청한 취소가 처리 중, 준비·진행
}

package negocut.auction.entity;

// 경매 상태. 등록 즉시 진행으로 바꾸고, 낙찰·유찰·중단은 종료 상태라 다시 진행으로 돌아가지 않는다. (기능 명세서 2-2)
public enum AuctionStatus {
    REGISTERED, IN_PROGRESS, SOLD, FAILED, STOPPED
}

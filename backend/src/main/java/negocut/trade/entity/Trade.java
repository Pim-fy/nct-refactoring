package negocut.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.auction.entity.AuctionResult;
import negocut.common.entity.BaseTimeEntity;
import negocut.member.entity.Member;

// 낙찰 한 건당 거래 한 건. 낙찰이 확정되면 판매자와 낙찰자를 당사자로 READY 상태로 만든다. (기능 명세서 3-8, DB 설계서 2-6)
// 배송지·발송·구매 확정·취소·분쟁과 상태 변경은 거래 구현에서 추가한다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "trade",
        uniqueConstraints = @UniqueConstraint(name = "uk_trade_auction_result", columnNames = "auction_result_id"))
public class Trade extends BaseTimeEntity {

    private static final int CONFIRM_DUE_DAYS = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_result_id", nullable = false)
    private AuctionResult auctionResult;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private Member seller;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private Member buyer;

    // 낙찰 금액으로 고정한다.
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal tradePrice;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private TradeStatus tradeStatus;

    // 낙찰 후 5일 안에 발송해야 한다.
    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime shipDueAt;

    // 발송 후 10일. 발송을 등록할 때 정한다.
    private LocalDateTime confirmDueAt;

    private LocalDateTime confirmedAt;

    // 완료 또는 취소된 시각
    private LocalDateTime completedAt;

    @Column(name = "is_auto_confirmed", nullable = false)
    private boolean autoConfirmed;

    // 발송 기한을 넘겼다는 표시. 거래 상태는 바꾸지 않는다.
    @Column(name = "is_unfulfilled", nullable = false)
    private boolean unfulfilled;

    private LocalDateTime unfulfilledAt;

    // 판매자가 발송을 등록했다. 진행 상태가 되고 구매 확정 기한은 발송 후 10일이다.
    public void startShipping(LocalDateTime shippedAt) {
        this.tradeStatus = TradeStatus.IN_PROGRESS;
        this.confirmDueAt = shippedAt.plusDays(CONFIRM_DUE_DAYS);
    }

    // 구매 확정으로 저장한다. 자동 구매 확정이면 그 사실도 남긴다.
    public void confirm(LocalDateTime confirmedAt, boolean auto) {
        this.tradeStatus = TradeStatus.CONFIRMED;
        this.confirmedAt = confirmedAt;
        this.autoConfirmed = auto;
    }

    // 양쪽이 합의해 거래를 취소한다. 취소는 최종 상태다.
    public void cancel(LocalDateTime canceledAt) {
        this.tradeStatus = TradeStatus.CANCELED;
        this.completedAt = canceledAt;
    }

    // 포인트를 지급하고 거래를 끝낸다.
    public void complete(LocalDateTime completedAt) {
        this.tradeStatus = TradeStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    // 발송 기한을 넘겼다는 표시만 남기고 거래 상태는 바꾸지 않는다.
    public void markUnfulfilled(LocalDateTime now) {
        this.unfulfilled = true;
        this.unfulfilledAt = now;
    }

    public static Trade open(AuctionResult result, Member seller, Member buyer, LocalDateTime shipDueAt) {
        Trade trade = new Trade();
        trade.auctionResult = result;
        trade.seller = seller;
        trade.buyer = buyer;
        trade.tradePrice = result.getWinningPrice();
        trade.tradeStatus = TradeStatus.READY;
        trade.shipDueAt = shipDueAt;
        trade.autoConfirmed = false;
        trade.unfulfilled = false;
        return trade;
    }
}

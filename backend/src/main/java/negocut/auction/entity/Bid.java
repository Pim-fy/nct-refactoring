package negocut.auction.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.common.entity.BaseTimeEntity;
import negocut.member.entity.Member;

// 입찰 한 건. 입찰은 철회할 수 없으므로 저장된 입찰은 모두 유효하다. (기능 명세서 3-6, DB 설계서 2-4)
// 현재 최고 입찰은 auction.top_bid_id로 판단하고, 입찰에는 포인트 예약이 유지 중인지만 남긴다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "bid",
        indexes = {
                @Index(name = "ix_bid_auction_price", columnList = "auction_id, bid_price"),
                @Index(name = "ix_bid_bidder_reserved", columnList = "bidder_id, is_reserved")
        })
public class Bid extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bidder_id", nullable = false)
    private Member bidder;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal bidPrice;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime bidAt;

    // 포인트 예약이 유지 중인지. 더 높은 입찰에 밀리거나 같은 회원이 더 높게 다시 입찰하면 예약이 새 입찰로 넘어가 false가 된다.
    @Column(name = "is_reserved", nullable = false)
    private boolean reserved;

    // 새 입찰은 포인트가 예약된 상태로 만든다.
    public static Bid place(Auction auction, Member bidder, BigDecimal bidPrice, LocalDateTime bidAt) {
        Bid bid = new Bid();
        bid.auction = auction;
        bid.bidder = bidder;
        bid.bidPrice = bidPrice;
        bid.bidAt = bidAt;
        bid.reserved = true;
        return bid;
    }

    // 예약이 다른 입찰로 넘어가거나 풀렸다.
    public void releaseReservation() {
        this.reserved = false;
    }
}

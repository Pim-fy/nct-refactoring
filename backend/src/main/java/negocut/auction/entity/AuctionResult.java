package negocut.auction.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import negocut.common.entity.BaseTimeEntity;
import negocut.member.entity.Member;

// 낙찰 정보. 경매당 한 건이며, 낙찰 금액은 종료 시점의 최고 입찰 금액으로 정해진 뒤 바뀌지 않는다. (기능 명세서 3-7, DB 설계서 2-6)
// 유찰이면 만들지 않는다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "auction_result",
        uniqueConstraints = @UniqueConstraint(name = "uk_auction_result_auction", columnNames = "auction_id"))
public class AuctionResult extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "winner_id", nullable = false)
    private Member winner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "winning_bid_id", nullable = false)
    private Bid winningBid;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal winningPrice;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime confirmedAt;

    public static AuctionResult confirm(Auction auction, Bid winningBid, LocalDateTime confirmedAt) {
        AuctionResult result = new AuctionResult();
        result.auction = auction;
        result.winner = winningBid.getBidder();
        result.winningBid = winningBid;
        result.winningPrice = winningBid.getBidPrice();
        result.confirmedAt = confirmedAt;
        return result;
    }
}

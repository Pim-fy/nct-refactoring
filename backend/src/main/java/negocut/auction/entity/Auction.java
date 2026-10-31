package negocut.auction.entity;

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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.common.entity.BaseTimeEntity;
import negocut.member.entity.Member;

// 경매 한 건. 상품의 거래 조건(시작가·입찰 단위·종료 시각)과 경매 상태, 현재 최고 입찰 정보를 가진다. (DB 설계서 2-4)
// 등록한 경매의 거래 조건은 수정하거나 삭제할 수 없다. (기능 명세서 3-4)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "auction",
        uniqueConstraints = @UniqueConstraint(name = "uk_auction_product", columnNames = "product_id"),
        indexes = {
                @Index(name = "ix_auction_status_end", columnList = "auction_status, end_at"),
                @Index(name = "ix_auction_status_created", columnList = "auction_status, created_at"),
                @Index(name = "ix_auction_status_price", columnList = "auction_status, current_price"),
                @Index(name = "ix_auction_status_bid_count", columnList = "auction_status, bid_count"),
                @Index(name = "ix_auction_seller_status", columnList = "seller_id, auction_status")
        })
public class Auction extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 상품당 경매는 한 건이다.
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private Member seller;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal startPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal bidUnit;

    // 입찰이 없으면 null이다.
    @Column(precision = 15, scale = 2)
    private BigDecimal currentPrice;

    // 현재 최고 입찰의 식별자. 입찰이 경매를 참조하므로 서로 참조하지 않게 식별자만 둔다.
    private Long topBidId;

    @Column(nullable = false)
    private int bidCount;

    @Column(nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AuctionStatus auctionStatus;

    private LocalDateTime closedAt;

    @Column(length = 500)
    private String stoppedReason;

    private Long stoppedByAdminId;

    private LocalDateTime stoppedAt;

    // 새 입찰이 최고 입찰이 된다. 현재가와 입찰 수, 최고 입찰 참조를 함께 갱신한다. (기능 명세서 3-6)
    // 입찰이 저장되어 식별자가 있어야 한다.
    public void applyBid(Bid bid) {
        this.currentPrice = bid.getBidPrice();
        this.topBidId = bid.getId();
        this.bidCount += 1;
    }

    // 종료 시각이 지난 경매를 낙찰 또는 유찰로 확정한다. 종료 처리 시각을 남긴다. (기능 명세서 3-4 경매 종료 처리)
    public void close(AuctionStatus result, LocalDateTime closedAt) {
        this.auctionStatus = result;
        this.closedAt = closedAt;
    }

    // 관리자가 등록 또는 진행 중인 경매를 중단한다. 중단한 경매는 목록에서 빠지고 입찰을 받지 않는다. 사유·관리자·시각을 남긴다. (기능 명세서 3-10 경매 강제 종료·숨김)
    // 남은 예약 포인트의 해제는 호출하는 쪽이 같은 트랜잭션에서 한다.
    public void stop(String reason, Long adminId, LocalDateTime stoppedAt) {
        if (auctionStatus != AuctionStatus.REGISTERED && auctionStatus != AuctionStatus.IN_PROGRESS) {
            throw new IllegalStateException("등록 또는 진행 중인 경매만 중단할 수 있습니다. auctionId=" + id + ", status=" + auctionStatus);
        }
        this.auctionStatus = AuctionStatus.STOPPED;
        this.stoppedReason = reason;
        this.stoppedByAdminId = adminId;
        this.stoppedAt = stoppedAt;
    }

    // 1차에서는 등록하면 바로 진행 상태로 공개한다. (기능 명세서 2-2)
    public static Auction register(Product product, Member seller, BigDecimal startPrice, BigDecimal bidUnit, LocalDateTime endAt) {
        Auction auction = new Auction();
        auction.product = product;
        auction.seller = seller;
        auction.startPrice = startPrice;
        auction.bidUnit = bidUnit;
        auction.bidCount = 0;
        auction.endAt = endAt;
        auction.auctionStatus = AuctionStatus.IN_PROGRESS;
        return auction;
    }
}

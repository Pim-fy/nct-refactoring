package negocut.point.entity;

import java.math.BigDecimal;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.auction.entity.Auction;
import negocut.common.entity.BaseTimeEntity;
import negocut.member.entity.Member;

// 포인트 변동 이력. 1차에서는 기록만 하고 조회 화면은 만들지 않는다. (기능 명세서 3-5, DB 설계서 2-5)
// 거래 엔티티는 거래 구현에서 만들므로, 그때까지 trade_id는 외래 키 없이 식별자만 둔다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "point_history",
        indexes = @Index(name = "ix_point_history_member_created", columnList = "member_id, created_at"))
public class PointHistory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private PointHistoryType historyType;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalPointAfter;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal reservedPointAfter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id")
    private Auction auction;

    private Long tradeId;

    // 처리 후 잔액을 함께 남긴다. 입찰·거래와 관련 없는 변동(충전)은 auction, tradeId가 null이다.
    // 금액은 항상 양수이고, 변동의 방향은 유형이 정한다. (충전·예약·해제·반환·지급)
    public static PointHistory record(Member member, PointHistoryType type, BigDecimal amount, PointBalance balanceAfter) {
        return record(member, type, amount, balanceAfter, null);
    }

    // 입찰과 관련된 변동(예약·해제)은 경매를 함께 남긴다.
    public static PointHistory record(Member member, PointHistoryType type, BigDecimal amount, PointBalance balanceAfter, Auction auction) {
        PointHistory history = new PointHistory();
        history.member = member;
        history.historyType = type;
        history.amount = amount;
        history.totalPointAfter = balanceAfter.getTotalPoint();
        history.reservedPointAfter = balanceAfter.getReservedPoint();
        history.auction = auction;
        return history;
    }
}

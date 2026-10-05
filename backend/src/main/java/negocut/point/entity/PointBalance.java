package negocut.point.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.common.entity.BaseTimeEntity;
import negocut.member.entity.Member;

// 회원가입 트랜잭션에서 함께 만들기 위해 1단계에서 필드만 먼저 둔다. 충전·예약 등 동작은 3단계에서 추가한다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "point_balance")
public class PointBalance extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)   // 회원당 잔액 1건. member_id에 UNIQUE가 걸린다.
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private Member member;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalPoint;      // 보유 포인트

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal reservedPoint;   // 예약 포인트

    // 보유·예약 포인트가 0인 잔액을 만든다.
    public static PointBalance createEmpty(Member member) {
        PointBalance balance = new PointBalance();
        balance.member = member;
        balance.totalPoint = BigDecimal.ZERO;
        balance.reservedPoint = BigDecimal.ZERO;
        return balance;
    }
}

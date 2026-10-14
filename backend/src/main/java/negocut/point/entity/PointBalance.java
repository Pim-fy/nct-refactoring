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

// 회원가입 트랜잭션에서 함께 만든다. 충전은 여기에 있고, 예약·해제·지급은 입찰·낙찰·거래 구현에서 추가한다.
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

    // 사용 가능 포인트 = 보유 − 예약
    public BigDecimal availablePoint() {
        return totalPoint.subtract(reservedPoint);
    }

    // 보유 포인트를 늘린다. 한도 확인은 호출하는 쪽에서 한다.
    public void charge(BigDecimal amount) {
        this.totalPoint = this.totalPoint.add(amount);
    }

    // 입찰로 포인트를 예약한다. 사용 가능 포인트 확인은 호출하는 쪽에서 한다.
    public void reserve(BigDecimal amount) {
        this.reservedPoint = this.reservedPoint.add(amount);
    }

    // 예약을 풀어 사용 가능 포인트로 되돌린다. 예약보다 많이 풀 수는 없다.
    public void release(BigDecimal amount) {
        if (amount.compareTo(reservedPoint) > 0) {
            throw new IllegalStateException("예약 포인트보다 많이 해제할 수 없습니다. reserved=" + reservedPoint + ", release=" + amount);
        }
        this.reservedPoint = this.reservedPoint.subtract(amount);
    }
}

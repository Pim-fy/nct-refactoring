package negocut.trade.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.common.entity.BaseTimeEntity;

// 거래의 배송지와 운송장. 거래당 한 건이며 낙찰자가 배송지를 입력할 때 만들어지고, 판매자가 발송을 등록하면 운송장이 채워진다.
// 당사자와 권한 있는 관리자에게만 보여 준다. (기능 명세서 3-8, 4-1, DB 설계서 2-6)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "trade_delivery",
        uniqueConstraints = @UniqueConstraint(name = "uk_trade_delivery_trade", columnNames = "trade_id"))
public class TradeDelivery extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trade_id", nullable = false)
    private Trade trade;

    @Column(nullable = false, length = 50)
    private String receiverName;

    @Column(nullable = false, length = 20)
    private String receiverPhone;

    @Column(nullable = false, length = 10)
    private String zipCode;

    @Column(nullable = false)
    private String address;

    private String addressDetail;

    private String deliveryMessage;

    @Column(length = 50)
    private String courierName;

    @Column(length = 50)
    private String trackingNumber;

    @Column(columnDefinition = "DATETIME(6)")
    private LocalDateTime shippedAt;

    public static TradeDelivery create(Trade trade, String receiverName, String receiverPhone, String zipCode,
            String address, String addressDetail, String deliveryMessage) {
        TradeDelivery delivery = new TradeDelivery();
        delivery.trade = trade;
        delivery.change(receiverName, receiverPhone, zipCode, address, addressDetail, deliveryMessage);
        return delivery;
    }

    // 발송 등록 전까지 배송지를 다시 입력할 수 있다.
    public void change(String receiverName, String receiverPhone, String zipCode,
            String address, String addressDetail, String deliveryMessage) {
        this.receiverName = receiverName;
        this.receiverPhone = receiverPhone;
        this.zipCode = zipCode;
        this.address = address;
        this.addressDetail = addressDetail;
        this.deliveryMessage = deliveryMessage;
    }

    public void ship(String courierName, String trackingNumber, LocalDateTime shippedAt) {
        this.courierName = courierName;
        this.trackingNumber = trackingNumber;
        this.shippedAt = shippedAt;
    }
}

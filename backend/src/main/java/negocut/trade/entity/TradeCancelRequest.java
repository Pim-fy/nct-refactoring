package negocut.trade.entity;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.common.entity.BaseTimeEntity;
import negocut.member.entity.Member;

// 거래 취소 요청. 한쪽이 요청하고 상대방이 수락하면 거래가 취소된다. (기능 명세서 3-8 거래 취소, DB 설계서 2-6)
// 거래당 처리 중인 요청은 한 건이다. MySQL에는 조건부 유니크 제약이 없어 거래 행을 잠근 뒤 서비스가 확인한다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "trade_cancel_request",
        indexes = @Index(name = "ix_trade_cancel_request_trade_status", columnList = "trade_id, request_status"))
public class TradeCancelRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trade_id", nullable = false)
    private Trade trade;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    private Member requester;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private CancelRequestStatus requestStatus;

    @Column(columnDefinition = "DATETIME(6)")
    private LocalDateTime respondedAt;

    public static TradeCancelRequest open(Trade trade, Member requester, String reason) {
        TradeCancelRequest request = new TradeCancelRequest();
        request.trade = trade;
        request.requester = requester;
        request.reason = reason;
        request.requestStatus = CancelRequestStatus.REQUESTED;
        return request;
    }

    public boolean isProcessing() {
        return requestStatus == CancelRequestStatus.REQUESTED;
    }

    public void accept(LocalDateTime respondedAt) {
        respond(CancelRequestStatus.ACCEPTED, respondedAt);
    }

    public void reject(LocalDateTime respondedAt) {
        respond(CancelRequestStatus.REJECTED, respondedAt);
    }

    // 거래가 다른 상태로 바뀌어 요청이 의미를 잃었을 때 시스템이 닫는다.
    public void close(LocalDateTime closedAt) {
        respond(CancelRequestStatus.CLOSED, closedAt);
    }

    private void respond(CancelRequestStatus status, LocalDateTime at) {
        this.requestStatus = status;
        this.respondedAt = at;
    }
}

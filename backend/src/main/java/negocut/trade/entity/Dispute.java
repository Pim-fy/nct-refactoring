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

// 거래 분쟁. 당사자가 접수하면 거래가 분쟁 상태가 되어 관리자 판정을 기다린다. (기능 명세서 3-9 분쟁 접수, DB 설계서 2-6)
// 거래당 처리 중인 분쟁은 한 건이다. MySQL에는 조건부 유니크 제약이 없어 거래 행을 잠근 뒤 서비스가 확인한다.
// 접수 직전 거래 상태를 저장해 두었다가 거래 진행으로 판정하면 그 상태로 되돌린다. 판정 필드는 관리자 판정 구현에서 채운다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "dispute",
        indexes = @Index(name = "ix_dispute_trade_status", columnList = "trade_id, dispute_status"))
public class Dispute extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trade_id", nullable = false)
    private Trade trade;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private Member reporter;

    @Column(nullable = false, length = 500)
    private String reason;

    // 접수 직전 거래 상태. 준비 또는 진행이다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private TradeStatus previousTradeStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private DisputeStatus disputeStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private DisputeJudgment judgment;

    @Column(length = 500)
    private String judgmentReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id")
    private Member admin;

    @Column(columnDefinition = "DATETIME(6)")
    private LocalDateTime judgedAt;

    public static Dispute open(Trade trade, Member reporter, String reason) {
        Dispute dispute = new Dispute();
        dispute.trade = trade;
        dispute.reporter = reporter;
        dispute.reason = reason;
        dispute.previousTradeStatus = trade.getTradeStatus();
        dispute.disputeStatus = DisputeStatus.OPEN;
        return dispute;
    }

    // 관리자가 판정한다. 처리 중인 분쟁만 판정할 수 있고, 보류 유지는 분쟁을 처리 중으로 둔 채 판정 결과만 남겨 다시 판정할 수 있다.
    // 그 밖의 판정은 분쟁을 판정 완료로 닫는다. 판정 결과와 사유, 판정한 관리자, 판정 시각을 남긴다.
    public void judge(DisputeJudgment judgment, String judgmentReason, Member admin, LocalDateTime judgedAt) {
        if (disputeStatus != DisputeStatus.OPEN) {
            throw new IllegalStateException("이미 판정이 끝난 분쟁입니다. disputeId=" + id);
        }
        this.judgment = judgment;
        this.judgmentReason = judgmentReason;
        this.admin = admin;
        this.judgedAt = judgedAt;
        if (judgment != DisputeJudgment.HOLD) {
            this.disputeStatus = DisputeStatus.JUDGED;
        }
    }

    public boolean isProcessing() {
        return disputeStatus == DisputeStatus.OPEN;
    }
}

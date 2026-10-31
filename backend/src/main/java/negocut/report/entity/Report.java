package negocut.report.entity;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.common.entity.BaseTimeEntity;
import negocut.member.entity.Member;

// 신고. 대상은 경매·회원·거래로 나뉘므로 외래 키 대신 target_type과 target_id로 연결한다. (기능 명세서 3-9, DB 설계서 2-7)
// 같은 회원이 같은 대상을 처리 전까지 한 건만 신고할 수 있다. MySQL에는 조건부 유니크 제약이 없어, 접수 상태일 때만 값을 갖는
// active_key(신고자·대상 유형·대상)에 유니크 제약을 걸어 동시에 들어온 중복 접수를 데이터베이스가 막는다. (NULL은 중복으로 보지 않는다.)
// 처리 완료·반려로 바꿀 때는 active_key를 비워야 같은 대상을 다시 신고할 수 있다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "report",
        uniqueConstraints = @UniqueConstraint(name = "uk_report_active_key", columnNames = "active_key"),
        indexes = {
                @Index(name = "ix_report_status_created", columnList = "report_status, created_at"),
                @Index(name = "ix_report_target", columnList = "target_type, target_id"),
                @Index(name = "ix_report_reporter_target", columnList = "reporter_id, target_type, target_id, report_status")
        })
public class Report extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private Member reporter;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ReportTargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private ReportReason reportReason;

    @Column(length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ReportStatus reportStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private ReportActionType actionType;

    @Column(length = 500)
    private String actionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id")
    private Member admin;

    @Column(columnDefinition = "DATETIME(6)")
    private LocalDateTime processedAt;

    // 접수 상태인 동안만 "신고자:대상 유형:대상 식별자"를 담는다.
    @Column(name = "active_key", length = 60)
    private String activeKey;

    // 관리자가 신고를 처리한다(경매 중단·회원 이용 제한). 접수 상태일 때만 처리할 수 있고, 같은 대상을 다시 신고할 수 있도록 중복 검사 키를 비운다.
    public void process(ReportActionType actionType, String actionReason, Member admin, LocalDateTime processedAt) {
        finish(ReportStatus.PROCESSED, actionType, actionReason, admin, processedAt);
    }

    // 관리자가 신고를 반려한다.
    public void reject(String actionReason, Member admin, LocalDateTime processedAt) {
        finish(ReportStatus.REJECTED, ReportActionType.REJECTED, actionReason, admin, processedAt);
    }

    private void finish(ReportStatus status, ReportActionType actionType, String actionReason, Member admin, LocalDateTime processedAt) {
        if (reportStatus != ReportStatus.RECEIVED) {
            throw new IllegalStateException("이미 처리된 신고입니다. reportId=" + id);
        }
        this.reportStatus = status;
        this.actionType = actionType;
        this.actionReason = actionReason;
        this.admin = admin;
        this.processedAt = processedAt;
        this.activeKey = null;
    }

    public static String activeKeyOf(Long reporterId, ReportTargetType targetType, Long targetId) {
        return reporterId + ":" + targetType + ":" + targetId;
    }

    public static Report receive(Member reporter, ReportTargetType targetType, Long targetId, ReportReason reason, String detail) {
        Report report = new Report();
        report.reporter = reporter;
        report.targetType = targetType;
        report.targetId = targetId;
        report.reportReason = reason;
        report.detail = detail;
        report.reportStatus = ReportStatus.RECEIVED;
        report.activeKey = activeKeyOf(reporter.getId(), targetType, targetId);
        return report;
    }
}

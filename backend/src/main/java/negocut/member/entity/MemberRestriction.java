package negocut.member.entity;

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

// 회원 이용 제한 이력. (기능 명세서 2-2, 3-10, DB 설계서 2-1)
// 회원의 현재 상태는 member.member_status에 있고, 이 행은 사유·기간·처리자를 남기는 이력이다.
// 한 회원의 현재 제한은 released_at이 비어 있는 행 하나이며, 수준을 바꾸면 기존 행을 해제로 닫고 새 행을 추가한다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "member_restriction",
        indexes = @Index(name = "ix_member_restriction_member", columnList = "member_id, released_at"))
public class MemberRestriction extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    // RESTRICTED_TRADE 또는 RESTRICTED_LOGIN. 현재 상태(member.member_status)와 같은 값으로 함께 갱신한다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private MemberStatus restrictionLevel;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "is_permanent", nullable = false)
    private boolean permanent;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime startedAt;

    // 기간을 지정한 제한의 만료 예정 시각. 영구 제한이면 비어 있다. 만료 때의 자동 해제는 2차에서 다룬다.
    @Column(columnDefinition = "DATETIME(6)")
    private LocalDateTime expiresAt;

    @Column(columnDefinition = "DATETIME(6)")
    private LocalDateTime releasedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_id", nullable = false)
    private Member admin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "released_by_admin_id")
    private Member releasedByAdmin;

    @Column(length = 500)
    private String releaseReason;

    public static MemberRestriction start(Member member, MemberStatus level, String reason, boolean permanent,
            LocalDateTime startedAt, LocalDateTime expiresAt, Member admin) {
        MemberRestriction restriction = new MemberRestriction();
        restriction.member = member;
        restriction.restrictionLevel = level;
        restriction.reason = reason;
        restriction.permanent = permanent;
        restriction.startedAt = startedAt;
        restriction.expiresAt = permanent ? null : expiresAt;
        restriction.admin = admin;
        return restriction;
    }

    // 제한을 닫는다. 해제하거나 다른 조건으로 교체할 때 쓴다. 이미 닫힌 행이면 거부한다.
    public void release(Member releasedBy, String releaseReason, LocalDateTime releasedAt) {
        if (this.releasedAt != null) {
            throw new IllegalStateException("이미 해제된 이용 제한입니다. restrictionId=" + id);
        }
        this.releasedAt = releasedAt;
        this.releasedByAdmin = releasedBy;
        this.releaseReason = releaseReason;
    }
}

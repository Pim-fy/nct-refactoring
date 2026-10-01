package negocut.auth.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "refresh_token", indexes = {
        @Index(name = "ix_refresh_token_member", columnList = "member_id, is_revoked"),
        @Index(name = "ix_refresh_token_expires", columnList = "expires_at")
})
public class RefreshToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false, length = 255)
    private String tokenHash;   // 토큰 원문이 아닌 해시 처리하여 저장 (DB 유출 시 토큰 도용 방지)

    @Column(nullable = false)
    private LocalDateTime expiresAt;    // 만료(expire) 예정 시각

    @Column(name = "is_revoked", nullable = false)
    private boolean revoked;    // 폐기(revoke) 여부

    public static RefreshToken create(Member member, String tokenHash, LocalDateTime expiresAt) {
        RefreshToken token = new RefreshToken();
        token.member = member;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt;
        token.revoked = false;
        return token;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public void revoke() {
        this.revoked = true;
    }
}

package negocut.auth.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.common.entity.BaseTimeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "email_verification", indexes = {
        @Index(name = "ix_email_verification_lookup", columnList = "email, purpose, expires_at")
})
public class EmailVerification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String email;

    @Enumerated(EnumType.STRING)    // enum을 DB 컬럼에 어떤 형태로 저장할지 정함. 이름 문자열로 저장한다는 뜻. VARCHAR 라고 지정하는 것은 아님.
    @JdbcTypeCode(SqlTypes.VARCHAR) // JDBC 타입을 VARCHAR로 취급하라는 Hibernate 전용 어노테이션
    @Column(nullable = false, length = 30)
    private VerificationPurpose purpose;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "is_used", nullable = false)
    private boolean used;

    public static EmailVerification create(String email, VerificationPurpose purpose, String code, LocalDateTime expiresAt) {
        EmailVerification verification = new EmailVerification();
        verification.email = email;
        verification.purpose = purpose;
        verification.code = code;
        verification.expiresAt = expiresAt;
        verification.used = false;
        return verification;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public void markUsed() {
        this.used = true;
    }
}

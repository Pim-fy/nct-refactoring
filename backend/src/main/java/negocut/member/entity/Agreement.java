package negocut.member.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.common.entity.BaseTimeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "agreement",
       uniqueConstraints = @UniqueConstraint(columnNames = {"agreement_type", "version"}))  // 여러 컬럼의 조합이 중복되지 않게 하는 DB 제약조건. agreement_type과 version을 묶음으로 봤을 때 중복을 막음.
public class Agreement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)    // enum을 DB 컬럼에 어떤 형태로 저장할지 정함. 이름 문자열로 저장한다는 뜻. VARCHAR 라고 지정하는 것은 아님.
    @JdbcTypeCode(SqlTypes.VARCHAR) // JDBC 타입을 VARCHAR로 취급하라는 Hibernate 전용 어노테이션
    @Column(nullable = false, length = 30)
    private AgreementType agreementType;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")    // 컬럼의 DB 타입을 SQL 문자열로 직접 지정하는 설정. 이 컬럼은 "TEXT"로 만들어라.
    private String content;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    @Column(nullable = false)
    private LocalDateTime effectiveAt;

    @Column(name = "is_active", nullable = false)
    private boolean active;
}

package negocut.member.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.common.entity.BaseTimeEntity;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "member_agreement",
       uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "agreement_id"}))  // 여러 컬럼의 조합이 중복되지 않게 하는 DB 제약조건. member_id와 agreement_id를 묶음으로 봤을 때 중복을 막음.
public class MemberAgreement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)  // 다대일(MemberAgreement N : Member 1). Member는 실제로 사용할 때 조회(지연 로딩)
    @JoinColumn(name = "member_id", nullable = false)   // FK 컬럼명(member_id), NOT NULL
    private Member member;  // N:1에서 1. FK 컬럼 member_id가 Member의 PK를 참조함

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agreement_id", nullable = false)
    private Agreement agreement;    // N:1에서 1. FK 컬럼 agreement_id가 Agreement의 PK를 참조함

    @Column(name = "is_agreed", nullable = false)
    private boolean agreed; 

    @Column(nullable = false)
    private LocalDateTime agreedAt;

    // 선택 약관에 동의하지 않은 이력도 남긴다.
    public static MemberAgreement create(Member member, Agreement agreement, boolean agreed, LocalDateTime agreedAt) {
        MemberAgreement memberAgreement = new MemberAgreement();
        memberAgreement.member = member;
        memberAgreement.agreement = agreement;
        memberAgreement.agreed = agreed;
        memberAgreement.agreedAt = agreedAt;
        return memberAgreement;
    }
}

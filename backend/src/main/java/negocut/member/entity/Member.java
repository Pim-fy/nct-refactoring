package negocut.member.entity;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.common.entity.BaseTimeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // 파라미터가 없는 기본 생성자를 만들고, 접근 범위를 protected로 제한.
@Entity
@Table(name = "member",
        // 제약에 이름을 붙여 두면 저장 시 중복 오류가 났을 때 어느 제약이 깨졌는지 이름으로 알 수 있다. (MemberService)
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_member_login_id", columnNames = "login_id"),
                @UniqueConstraint(name = "uk_member_nickname", columnNames = "nickname"),
                @UniqueConstraint(name = "uk_member_email", columnNames = "email")
        },
        indexes = {
                @Index(name = "ix_member_status_withdrawn", columnList = "member_status, withdrawn_at")
        })
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)     // AUTO_INCREMENT 사용
    private Long id;

    @Column(nullable = false, length = 20)
    private String loginId;

    @Column(nullable = false, length = 60)
    private String password;

    @Column(nullable = false, length = 10)
    private String nickname;

    @Column(nullable = false, length = 100)
    private String email;

    @Column(nullable = false, length = 20)
    private String phone;

    // image_file 참조. 엔티티는 2단계(이미지 업로드)에서 생기므로 지금은 평범한 값으로만 둔다.
    private Long profileImageId;

    @Enumerated(EnumType.STRING)    // enum을 DB 컬럼에 어떤 형태로 저장할지 정함. 이름 문자열로 저장한다는 뜻. VARCHAR 라고 지정하는 것은 아님.
    @JdbcTypeCode(SqlTypes.VARCHAR) // JDBC 타입을 VARCHAR로 취급하라는 Hibernate 전용 어노테이션
    @Column(nullable = false, length = 20)
    private MemberRole memberRole;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private MemberStatus memberStatus;

    // 탈퇴 시각. NULL이면 탈퇴하지 않은 회원
    private LocalDateTime withdrawnAt;

    // 가입한 회원은 일반 회원(MEMBER)이며 상태는 정상(NORMAL)이다. 비밀번호는 해시한 값을 받는다.
    public static Member create(String loginId, String encodedPassword, String nickname, String email, String phone) {
        Member member = new Member();
        member.loginId = loginId;
        member.password = encodedPassword;
        member.nickname = nickname;
        member.email = email;
        member.phone = phone;
        member.memberRole = MemberRole.MEMBER;
        member.memberStatus = MemberStatus.NORMAL;
        return member;
    }

    // 관리자가 이용 제한을 걸거나 수준을 바꾼다. 현재 상태를 제한 수준으로 바꾸며, 탈퇴 회원에는 쓸 수 없다. (기능 명세서 2-2)
    public void restrict(MemberStatus level) {
        if (level != MemberStatus.RESTRICTED_TRADE && level != MemberStatus.RESTRICTED_LOGIN) {
            throw new IllegalArgumentException("이용 제한 수준이 아닙니다. level=" + level);
        }
        if (memberStatus == MemberStatus.WITHDRAWN) {
            throw new IllegalStateException("탈퇴한 회원은 이용 제한을 걸 수 없습니다. memberId=" + id);
        }
        this.memberStatus = level;
    }

    // 관리자가 이용 제한을 해제하면 정상으로 돌아간다. 제한 중인 회원에만 쓸 수 있다.
    public void releaseRestriction() {
        if (memberStatus != MemberStatus.RESTRICTED_TRADE && memberStatus != MemberStatus.RESTRICTED_LOGIN) {
            throw new IllegalStateException("이용 제한 중인 회원이 아닙니다. memberId=" + id);
        }
        this.memberStatus = MemberStatus.NORMAL;
    }

    // 프로필로 쓰던 이미지를 지울 때 참조를 비운다. 화면은 이때 기본 이미지를 보여준다. (기능 명세서 3-3)
    public void clearProfileImage() {
        this.profileImageId = null;
    }
}

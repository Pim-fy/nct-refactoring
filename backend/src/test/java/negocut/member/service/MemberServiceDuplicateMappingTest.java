package negocut.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLIntegrityConstraintViolationException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import negocut.common.exception.ErrorCode;

// 저장 시 DB 유니크 제약 위반을 어느 값의 중복 오류로 바꾸는지 시험한다. MySQL이 내는 메시지 형태를 흉내 낸다.
class MemberServiceDuplicateMappingTest {

    private DataIntegrityViolationException violation(String key) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLIntegrityConstraintViolationException("Duplicate entry 'x' for key '" + key + "'"));
    }

    @Test
    void 제약_이름으로_중복된_값을_알아낸다() {
        assertThat(MemberService.duplicateErrorCodeOf(violation("member.uk_member_login_id"))).isEqualTo(ErrorCode.MEMBER_ID_DUPLICATED);
        assertThat(MemberService.duplicateErrorCodeOf(violation("member.uk_member_nickname"))).isEqualTo(ErrorCode.MEMBER_NICKNAME_DUPLICATED);
        assertThat(MemberService.duplicateErrorCodeOf(violation("member.uk_member_email"))).isEqualTo(ErrorCode.MEMBER_EMAIL_DUPLICATED);
    }

    @Test
    void 대문자로_보고되어도_구분한다() {
        assertThat(MemberService.duplicateErrorCodeOf(violation("MEMBER.UK_MEMBER_EMAIL"))).isEqualTo(ErrorCode.MEMBER_EMAIL_DUPLICATED);
    }

    @Test
    void 알_수_없는_제약은_null이라_원래_예외가_그대로_나간다() {
        assertThat(MemberService.duplicateErrorCodeOf(violation("member.something_else"))).isNull();
        assertThat(MemberService.duplicateErrorCodeOf(new DataIntegrityViolationException("no cause"))).isNull();
    }
}

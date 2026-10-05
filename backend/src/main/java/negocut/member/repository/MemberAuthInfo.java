package negocut.member.repository;

import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;

// 인증 필터가 요청마다 읽는 최소 정보. 회원 엔티티 전체 대신 이 두 컬럼만 조회한다.
public interface MemberAuthInfo {

    MemberRole getMemberRole();

    MemberStatus getMemberStatus();
}

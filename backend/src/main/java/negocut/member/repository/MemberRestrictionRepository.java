package negocut.member.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import negocut.member.entity.MemberRestriction;

public interface MemberRestrictionRepository extends JpaRepository<MemberRestriction, Long> {

    // 회원의 현재 제한. 해제 시각이 비어 있는 행이며 정상이면 하나다. 데이터가 어긋나 둘 이상이어도 읽을 수 있게 목록으로 읽고, 가장 최근 행이 현재 제한이다.
    List<MemberRestriction> findByMemberIdAndReleasedAtIsNullOrderByIdDesc(Long memberId);

    // 관리자 회원 목록에 보여 줄 현재 제한을 한 번에 읽는다.
    List<MemberRestriction> findByMemberIdInAndReleasedAtIsNull(Collection<Long> memberIds);
}

package negocut.auth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import negocut.auth.entity.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // (member_id, is_revoked) 인덱스를 쓴다. 토큰 해시는 인덱스가 없어 직접 찾지 않고 회원의 유효 토큰을 가져와 비교한다.
    List<RefreshToken> findByMemberIdAndRevokedFalse(Long memberId);
}

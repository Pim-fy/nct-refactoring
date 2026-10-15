package negocut.point.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.auction.entity.Auction;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.member.entity.Member;
import negocut.member.repository.MemberRepository;
import negocut.point.dto.PointResponse;
import negocut.point.entity.PointBalance;
import negocut.point.entity.PointHistory;
import negocut.point.entity.PointHistoryType;
import negocut.point.repository.PointBalanceRepository;
import negocut.point.repository.PointHistoryRepository;

// 내 포인트 조회와 충전, 입찰의 포인트 예약·해제. (기능 명세서 3-5) 반환·지급은 거래 구현에서 추가한다.
@Service
@RequiredArgsConstructor
public class PointService {

    private static final BigDecimal CHARGE_UNIT = BigDecimal.valueOf(1000);
    private static final BigDecimal BALANCE_LIMIT = BigDecimal.valueOf(10_000_000);

    private final PointBalanceRepository pointBalanceRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public PointResponse getMyPoints(Long memberId) {
        return PointResponse.from(findBalance(memberId));
    }

    // 잔액 행을 먼저 잠그고 읽는다. 같은 회원의 충전 요청이 겹쳐도 보유 한도(1,000만)를 넘지 않고, 이력의 처리 후 잔액이 이어진다.
    // READ_COMMITTED로 실행해, 잠금을 기다린 사이에 다른 요청이 커밋한 잔액을 읽는다. (REPEATABLE READ에서는 읽기 스냅샷이 고정될 수 있다.)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PointResponse charge(Long memberId, BigDecimal amount) {
        requireValidAmount(amount);

        PointBalance balance = pointBalanceRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new IllegalStateException("포인트 잔액이 없는 회원입니다. memberId=" + memberId));
        if (balance.getTotalPoint().add(amount).compareTo(BALANCE_LIMIT) > 0) {
            throw new BusinessException(ErrorCode.POINT_BALANCE_LIMIT_EXCEEDED);
        }

        balance.charge(amount);
        Member member = memberRepository.getReferenceById(memberId);
        pointHistoryRepository.save(PointHistory.record(member, PointHistoryType.CHARGE, amount, balance));
        return PointResponse.from(balance);
    }

    // 입찰로 포인트를 예약한다. 호출하는 쪽이 잔액 행을 잠근 트랜잭션 안에서 불러야 한다. (입찰 처리)
    // 사용 가능 포인트(보유 − 예약)를 넘으면 예약하지 않고 거부한다.
    public void reserve(PointBalance balance, Member member, BigDecimal amount, Auction auction) {
        if (balance.availablePoint().compareTo(amount) < 0) {
            throw new BusinessException(ErrorCode.POINT_NOT_ENOUGH);
        }
        balance.reserve(amount);
        pointHistoryRepository.save(PointHistory.record(member, PointHistoryType.RESERVE, amount, balance, auction));
    }

    // 구매 확정으로 거래 대금을 지급한다. 낙찰자의 예약 포인트가 보유 포인트와 함께 나가고 판매자의 보유 포인트가 늘어난다.
    // 낙찰자·판매자 각각에 지급 이력을 남기고 거래를 연결한다. 방향은 이력의 회원과 거래의 역할로 판단한다.
    // 호출하는 쪽이 두 잔액 행을 회원 번호 순서로 잠근 트랜잭션 안에서 불러야 한다.
    public void payout(PointBalance buyerBalance, Member buyer, PointBalance sellerBalance, Member seller, BigDecimal amount, Long tradeId) {
        buyerBalance.payOut(amount);
        pointHistoryRepository.save(PointHistory.recordForTrade(buyer, PointHistoryType.PAYOUT, amount, buyerBalance, tradeId));
        sellerBalance.receive(amount);
        pointHistoryRepository.save(PointHistory.recordForTrade(seller, PointHistoryType.PAYOUT, amount, sellerBalance, tradeId));
    }

    // 더 높은 입찰에 밀린 입찰자의 예약을 푼다. 호출하는 쪽이 잔액 행을 잠근 트랜잭션 안에서 불러야 한다.
    public void release(PointBalance balance, Member member, BigDecimal amount, Auction auction) {
        balance.release(amount);
        pointHistoryRepository.save(PointHistory.record(member, PointHistoryType.RELEASE, amount, balance, auction));
    }

    // 범위(1,000~100만)는 요청 형식에서 확인했다. 여기서는 정수 표기이고 1,000 단위인지 본다.
    // 100000.0이나 1E+5 같은 표기는 값이 정수여도 거부한다. 같은 금액을 다른 표기로 보내 중복 요청 방지를 피하거나, 응답에 입력의 자릿수가 남는 것을 막기 위해서다.
    // 범위 확인을 통과한 값만 오므로, 지수 표기로 아주 큰 값이 와도 나머지 계산이 오래 걸리지 않는다.
    private void requireValidAmount(BigDecimal amount) {
        if (amount == null || amount.scale() != 0 || amount.remainder(CHARGE_UNIT).signum() != 0) {
            throw new BusinessException(ErrorCode.POINT_CHARGE_AMOUNT_INVALID);
        }
    }

    private PointBalance findBalance(Long memberId) {
        return pointBalanceRepository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalStateException("포인트 잔액이 없는 회원입니다. memberId=" + memberId));
    }
}

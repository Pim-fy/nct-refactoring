import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getErrorMessage } from '../api/apiResponse';
import { placeBid } from '../api/bidApi';
import { fetchMyPoints } from '../api/pointApi';
import { useToast } from '../hooks/useToast';
import { formatPoint, formatWon } from '../utils/format';
import Modal from './Modal';

const QUICK_STEPS = [1, 5, 10];
const MAX_BID = 1_000_000_000;   // 서버의 입찰 금액 상한과 같다.

// 입찰 영역 (화면 설계서 3-3, 기능 명세서 3-6). 입찰할 수 있는 회원(canBid)에게만 보여 준다.
// 입력란에는 최소 입찰 금액을 채우고, 입찰은 철회할 수 없으므로 확인 단계를 거친다.
// 최소 입찰 금액이 바뀌면(다른 입찰이 들어오면) 부모가 key를 바꿔 이 영역을 새로 만든다.
export default function BidPanel({ auctionId, bidding, bidUnit, onChanged }) {
  const toast = useToast();
  const minimum = Number(bidding.minimumBidPrice);
  const unit = Number(bidUnit);
  const currentPrice = bidding.currentPrice === null || bidding.currentPrice === undefined ? null : Number(bidding.currentPrice);

  const [input, setInput] = useState(String(minimum));
  const [available, setAvailable] = useState(null);   // 사용 가능 포인트. null이면 아직 모른다.
  const [confirming, setConfirming] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchMyPoints()
      .then((points) => {
        if (!cancelled) {
          setAvailable(Number(points.availablePoint));
        }
      })
      .catch(() => {});   // 포인트를 받지 못해도 입찰은 서버가 판단한다.
    return () => {
      cancelled = true;
    };
  }, []);

  const amount = input === '' ? null : Number(input);
  // 예약에 필요한 포인트: 최고 입찰자가 본인이면 입찰 금액에서 기존 예약(현재가)을 뺀 값, 아니면 입찰 금액 전체
  const needed = amount === null ? null : bidding.isTopBidder && currentPrice !== null ? amount - currentPrice : amount;

  let problem = null;
  let shortage = false;   // 포인트가 모자라 충전이 필요한 경우
  if (amount !== null) {
    if (amount > MAX_BID) {
      problem = '입찰 금액은 10억원 이하여야 합니다.';
    } else if (amount % unit !== 0) {
      problem = `입찰 금액은 입찰 단위(${formatWon(unit)})의 배수여야 합니다.`;
    } else if (amount < minimum) {
      problem = `최소 입찰 금액은 ${formatWon(minimum)}입니다.`;
    } else if (available !== null && needed > available) {
      problem = `사용 가능 포인트가 부족합니다. (필요 ${formatPoint(needed)}, 사용 가능 ${formatPoint(available)})`;
      shortage = true;
    }
  }
  const canBid = amount !== null && problem === null;

  function handleInput(event) {
    setInput(event.target.value.replace(/[^0-9]/g, '').slice(0, 10));
  }

  function addSteps(steps) {
    setInput(String((amount ?? minimum) + unit * steps));
  }

  async function confirmBid() {
    setSubmitting(true);
    try {
      const result = await placeBid(auctionId, amount);
      setConfirming(false);
      setAvailable(Number(result.point.availablePoint));
      toast.success(`${formatWon(amount)}에 입찰했습니다.`);
      onChanged();
    } catch (error) {
      setConfirming(false);
      toast.error(getErrorMessage(error));
      onChanged();   // 다른 입찰이 먼저 들어왔을 수 있으므로 경매 정보를 다시 받는다.
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mt-4 rounded-box border border-base-300 p-4">
      <h2 className="mb-1 font-bold">입찰하기</h2>
      {bidding.isTopBidder && <p className="mb-2 text-sm text-success">현재 최고 입찰자입니다. 더 높게 입찰하면 차액만 추가로 예약됩니다.</p>}
      <p className="mb-2 text-sm text-base-content/70">
        최소 입찰 금액 {formatWon(minimum)} · 입찰 단위 {formatWon(unit)}
        {available !== null && ` · 사용 가능 ${formatPoint(available)}`}
      </p>

      <div className="join w-full">
        <input
          inputMode="numeric"
          className={`input input-bordered join-item w-full ${problem ? 'input-error' : ''}`}
          value={amount === null ? '' : amount.toLocaleString('ko-KR')}
          placeholder="입찰 금액"
          aria-label="입찰 금액"
          onChange={handleInput}
        />
        <span className="btn btn-disabled join-item no-animation">원</span>
      </div>
      {problem && (
        <p role="alert" className="mt-1 text-sm text-error">
          {problem}{' '}
          {shortage && (
            <Link to="/my/points" className="link link-primary">
              포인트 충전
            </Link>
          )}
        </p>
      )}

      <div className="mt-2 flex flex-wrap gap-2">
        {QUICK_STEPS.map((steps) => (
          <button key={steps} type="button" className="btn btn-sm" onClick={() => addSteps(steps)}>
            +{steps}단위
          </button>
        ))}
        <button type="button" className="btn btn-sm btn-ghost" onClick={() => setInput(String(minimum))}>
          최소 금액
        </button>
      </div>

      <button type="button" className="btn btn-primary mt-3 w-full" disabled={!canBid || submitting} onClick={() => setConfirming(true)}>
        입찰하기
      </button>

      <Modal open={confirming} title="이 금액으로 입찰할까요?" onClose={() => setConfirming(false)}>
        {amount !== null && (
          <>
            <dl className="mb-4 grid grid-cols-[8rem_1fr] gap-y-2 text-sm">
              <dt className="text-base-content/60">입찰 금액</dt>
              <dd className="font-medium">{formatWon(amount)}</dd>
              <dt className="text-base-content/60">예약되는 포인트</dt>
              <dd>{formatPoint(needed)}</dd>
              {available !== null && (
                <>
                  <dt className="text-base-content/60">입찰 후 사용 가능</dt>
                  <dd>{formatPoint(available - needed)}</dd>
                </>
              )}
            </dl>
            <p className="mb-4 rounded bg-warning/20 p-3 text-sm">
              입찰은 철회할 수 없습니다. 더 높은 입찰이 들어오면 예약된 포인트는 풀립니다.
            </p>
            <button type="button" className="btn btn-primary" disabled={submitting} onClick={confirmBid}>
              {submitting ? '입찰 중...' : '입찰하기'}
            </button>
          </>
        )}
      </Modal>
    </div>
  );
}

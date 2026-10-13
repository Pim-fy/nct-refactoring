import { useEffect, useState } from 'react';
import { getErrorMessage } from '../api/apiResponse';
import { chargePoints, fetchMyPoints } from '../api/pointApi';
import FormField from '../components/FormField';
import Modal from '../components/Modal';
import { useToast } from '../hooks/useToast';
import { formatPoint } from '../utils/format';

// 충전 한도. 서버의 규칙과 같다. (기능 명세서 3-5)
const CHARGE_UNIT = 1000;
const MAX_CHARGE = 1_000_000;
const MAX_BALANCE = 10_000_000;

const QUICK_AMOUNTS = [
  { label: '+1만', value: 10_000 },
  { label: '+5만', value: 50_000 },
  { label: '+10만', value: 100_000 },
  { label: '+100만', value: 1_000_000 },
];

// 입력한 금액의 문제를 알려 준다. 문제가 없으면 null이다. 최종 판단은 서버가 한다.
function validateAmount(amount, totalPoint) {
  if (amount === null) {
    return null;
  }
  if (amount < CHARGE_UNIT || amount % CHARGE_UNIT !== 0) {
    return '충전 금액은 1,000 포인트 단위로 입력해 주세요.';
  }
  if (amount > MAX_CHARGE) {
    return '1회 충전 금액은 100만 포인트까지입니다.';
  }
  if (totalPoint !== null && totalPoint + amount > MAX_BALANCE) {
    return `충전 후 보유 포인트가 1,000만 포인트를 넘을 수 없습니다. (충전 가능 ${formatPoint(MAX_BALANCE - totalPoint)})`;
  }
  return null;
}

// 포인트 충전 (화면 설계서 3-6, 기능 명세서 3-5)
export default function PointPage() {
  const toast = useToast();
  const [points, setPoints] = useState(null);   // null이면 불러오는 중이다.
  const [failed, setFailed] = useState(false);
  const [input, setInput] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchMyPoints()
      .then((result) => {
        if (!cancelled) {
          setPoints(result);
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setFailed(true);
          toast.error(getErrorMessage(error));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [toast]);

  const digits = input.replace(/[^0-9]/g, '');
  const amount = digits === '' ? null : Number(digits);
  const totalPoint = points === null ? null : Number(points.totalPoint);
  const problem = validateAmount(amount, totalPoint);
  const canCharge = points !== null && amount !== null && problem === null;

  function handleInput(event) {
    setInput(event.target.value.replace(/[^0-9]/g, '').slice(0, 8));
  }

  function addQuick(value) {
    setInput(String(Math.min((amount ?? 0) + value, 99_999_999)));
  }

  async function confirmCharge() {
    setSubmitting(true);
    try {
      const result = await chargePoints(amount);
      setPoints(result);
      setInput('');
      setConfirming(false);
      toast.success(`${formatPoint(amount)}를 충전했습니다.`);
    } catch (error) {
      setConfirming(false);
      toast.error(getErrorMessage(error));
      // 다른 곳에서 충전해 한도에 걸린 경우처럼 화면의 잔액이 낡았을 수 있으므로 다시 받는다.
      fetchMyPoints()
        .then((result) => setPoints(result))
        .catch(() => {});
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-lg rounded-box bg-base-100 p-6 shadow-sm">
      <h1 className="mb-4 text-xl font-bold">포인트 충전</h1>

      {points === null && !failed && (
        <div className="flex justify-center py-6">
          <span className="loading loading-spinner loading-lg text-primary" />
        </div>
      )}
      {failed && <p className="py-6 text-center text-base-content/60">포인트 정보를 불러오지 못했습니다.</p>}

      {points !== null && (
        <>
          <div className="mb-6 rounded-box bg-base-200 p-4">
            <p className="text-sm text-base-content/60">보유 포인트</p>
            <p className="text-3xl font-bold text-primary">{formatPoint(points.totalPoint)}</p>
            <p className="mt-1 text-sm text-base-content/70">
              예약 {formatPoint(points.reservedPoint)} / 사용 가능 {formatPoint(points.availablePoint)}
            </p>
          </div>

          <FormField
            label="충전 금액"
            htmlFor="charge-amount"
            error={problem}
            hint="1,000 포인트 단위로 1회 최대 100만 포인트까지 충전할 수 있습니다. 충전 후 보유 포인트는 1,000만 포인트를 넘을 수 없습니다."
          >
            <div className="join w-full">
              <input
                id="charge-amount"
                inputMode="numeric"
                className={`input input-bordered join-item w-full ${problem ? 'input-error' : ''}`}
                value={amount === null ? '' : amount.toLocaleString('ko-KR')}
                placeholder="충전할 금액"
                onChange={handleInput}
              />
              <span className="btn btn-disabled join-item no-animation">P</span>
            </div>
          </FormField>

          <div className="mb-4 flex flex-wrap gap-2">
            {QUICK_AMOUNTS.map((quick) => (
              <button key={quick.value} type="button" className="btn btn-sm" onClick={() => addQuick(quick.value)}>
                {quick.label}
              </button>
            ))}
            <button type="button" className="btn btn-sm btn-ghost" onClick={() => setInput('')}>
              지우기
            </button>
          </div>

          <button type="button" className="btn btn-primary w-full" disabled={!canCharge || submitting} onClick={() => setConfirming(true)}>
            충전하기
          </button>
        </>
      )}

      <Modal open={confirming} title="이 금액을 충전할까요?" onClose={() => setConfirming(false)}>
        {points !== null && amount !== null && (
          <>
            <dl className="mb-4 grid grid-cols-[8rem_1fr] gap-y-2 text-sm">
              <dt className="text-base-content/60">충전 금액</dt>
              <dd className="font-medium">{formatPoint(amount)}</dd>
              <dt className="text-base-content/60">현재 보유</dt>
              <dd>{formatPoint(points.totalPoint)}</dd>
              <dt className="text-base-content/60">충전 후 보유</dt>
              <dd>{formatPoint(Number(points.totalPoint) + amount)}</dd>
            </dl>
            <p className="mb-4 rounded bg-warning/20 p-3 text-sm">충전한 포인트는 취소할 수 없습니다. 금액을 다시 확인해 주세요.</p>
            <button type="button" className="btn btn-primary" disabled={submitting} onClick={confirmCharge}>
              {submitting ? '충전 중...' : '충전하기'}
            </button>
          </>
        )}
      </Modal>
    </div>
  );
}

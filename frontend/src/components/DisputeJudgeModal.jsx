import { useEffect, useState } from 'react';
import { fetchAdminTrade, judgeDispute } from '../api/adminDisputeApi';
import { getErrorCode, getErrorMessage } from '../api/apiResponse';
import { useToast } from '../hooks/useToast';
import {
  DISPUTE_JUDGMENT_LABELS,
  DISPUTE_REPORTER_LABELS,
  disputeStateBadge,
  disputeStateLabel,
  formatDateTime,
  formatWon,
  isMeaningfulReason,
  TRADE_STATUS_LABELS,
  trimReason,
} from '../utils/format';
import Modal from './Modal';

const MAX_REASON = 500;
const JUDGMENTS = ['REFUND', 'PAYOUT', 'RESUME', 'HOLD'];
const DUE_LABELS = { SHIP: '발송 기한', CONFIRM: '구매 확정 기한' };
// 포인트가 움직이는 판정은 확인란을 한 번 더 체크해야 한다.
const MONEY_JUDGMENTS = ['REFUND', 'PAYOUT'];

// 분쟁 상세·판정 모달 (기능 명세서 3-10 분쟁 판정). item은 관리자 분쟁 목록의 항목이다.
// 거래 정보·배송 정보·분쟁 이력을 보여 주고, 처리 중인 분쟁이면 판정 4종 중 하나를 사유와 함께 제출한다.
// 반환·지급은 포인트가 움직이므로 확인란에 체크해야 하고, 지급은 발송이 등록된 거래에서만 고를 수 있다.
// 거래 진행은 기한을 어떻게 정하는지 미리 알려 주되 판정하는 시각 기준으로 확정되는 예상 값이다.
// 요청에는 화면에 보인 마지막 판정 시각을 실어 보내며, 그사이 다른 관리자가 판정(보류 포함)했으면 서버가 409로 거부하고 최신 내용을 다시 받아 보여 준다.
// 열 때마다 새로 시작하도록 부모가 key를 분쟁 식별자로 준다.
export default function DisputeJudgeModal({ item, onClose, onDone }) {
  return (
    <Modal open={item !== null} title="분쟁 상세·판정" onClose={onClose}>
      {item && <DisputeJudgeBody item={item} onDone={onDone} />}
    </Modal>
  );
}

function DisputeJudgeBody({ item, onDone }) {
  const toast = useToast();
  const [result, setResult] = useState({ trade: null, failed: false });
  const [judgment, setJudgment] = useState('');
  const [reason, setReason] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    let cancelled = false;
    fetchAdminTrade(item.tradeId)
      .then((trade) => {
        if (!cancelled) setResult({ trade, failed: false });
      })
      .catch((error) => {
        if (!cancelled) {
          setResult({ trade: null, failed: true });
          toast.error(getErrorMessage(error));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [item.tradeId, refresh, toast]);

  const trade = result.trade;
  const dispute = trade?.disputes.find((d) => d.disputeId === item.disputeId) ?? null;
  const judgeable = dispute !== null && dispute.disputeStatus === 'OPEN' && trade.tradeStatus === 'DISPUTED';
  const payoutAllowed = dispute?.previousTradeStatus === 'IN_PROGRESS';

  const trimmedReason = trimReason(reason);
  const reasonProblem =
    trimmedReason.length > MAX_REASON
      ? `판정 사유는 ${MAX_REASON}자 이하로 입력해 주세요.`
      : !isMeaningfulReason(trimmedReason)
        ? '판정 사유를 입력해 주세요.'
        : null;
  const problem =
    judgment === ''
      ? '판정을 선택해 주세요.'
      : reasonProblem ?? (MONEY_JUDGMENTS.includes(judgment) && !confirmed ? '포인트가 움직이는 판정입니다. 위 확인란에 체크해 주세요.' : null);

  function describe(value) {
    const price = formatWon(trade.tradePrice);
    if (value === 'REFUND') return `${trade.buyerNickname}님의 예약 포인트 ${price}을 반환하고 거래를 취소합니다. 되돌릴 수 없습니다.`;
    if (value === 'PAYOUT') return `${trade.buyerNickname}님의 예약 포인트 ${price}을 ${trade.sellerNickname}님에게 지급하고 거래를 완료합니다. 되돌릴 수 없습니다.`;
    if (value === 'RESUME') {
      const due = dispute.resumeDue;
      if (!due) return '분쟁을 닫고 거래를 접수 직전 상태로 되돌립니다. 포인트는 그대로입니다.';
      const label = DUE_LABELS[due.dueType];
      return due.adjusted
        ? `분쟁을 닫고 거래를 접수 직전 상태(${TRADE_STATUS_LABELS[dispute.previousTradeStatus]})로 되돌립니다. 원래 ${label}(${formatDateTime(due.currentDueAt)})까지 2시간 미만이거나 이미 지나서, ${label}은 판정하는 시각부터 2시간 뒤로 정해집니다(지금 기준 예상 ${formatDateTime(due.resumedDueAt)}).`
        : `분쟁을 닫고 거래를 접수 직전 상태(${TRADE_STATUS_LABELS[dispute.previousTradeStatus]})로 되돌립니다. ${label}은 원래대로 ${formatDateTime(due.resumedDueAt)}까지입니다. 판정하는 시각에 2시간 미만으로 남아 있으면 그 시각부터 2시간으로 정해집니다.`;
    }
    return '거래를 분쟁 상태로 두고 분쟁도 처리 중으로 남깁니다. 추가 확인 뒤 다시 판정할 수 있습니다.';
  }

  async function submit() {
    setSubmitting(true);
    try {
      const done = await judgeDispute(item.disputeId, {
        judgment,
        judgmentReason: trimmedReason,
        expectedJudgedAt: dispute.judgedAt ?? undefined,
      });
      // 거래 진행이면 실제로 정해진 기한을 알린다. 2시간으로 조정되었으면 그 사실도 함께 알린다.
      const dueNote = done.dueAt ? ` ${DUE_LABELS[done.dueType]}은 ${formatDateTime(done.dueAt)}까지입니다${done.isDueAdjusted ? '(2시간으로 조정됨)' : ''}.` : '';
      toast.success(`${DISPUTE_JUDGMENT_LABELS[done.judgment]} 판정을 완료했습니다.${dueNote}`);
      onDone(item.disputeId);
    } catch (error) {
      toast.error(getErrorMessage(error));
      const code = getErrorCode(error);
      // 이미 판정이 끝났거나 없는 분쟁이면 되풀이해도 소용없으므로 닫고 목록을 새로 받는다.
      if (code === 'DISPUTE_ALREADY_CLOSED' || code === 'DISPUTE_NOT_FOUND' || code === 'TRADE_STATUS_INVALID') {
        onDone(item.disputeId);
      } else if (code === 'DISPUTE_CHANGED') {
        // 그사이 다른 관리자가 판정(보류 포함)했다. 최신 분쟁 이력을 다시 받아 보여 주고 확인란은 다시 체크받는다.
        setConfirmed(false);
        setRefresh((count) => count + 1);
      }
    } finally {
      setSubmitting(false);
    }
  }

  if (result.trade === null) {
    return result.failed ? (
      <p className="py-6 text-center text-base-content/60">거래 정보를 불러오지 못했습니다.</p>
    ) : (
      <div className="flex justify-center py-8">
        <span className="loading loading-spinner loading-lg text-primary" />
      </div>
    );
  }

  const delivery = trade.delivery;
  return (
    <>
      <section className="mb-4 rounded-box bg-base-200 p-3 text-sm">
        <p className="font-medium">{trade.auction.productName}</p>
        <p className="text-base-content/70">
          거래가 {formatWon(trade.tradePrice)} · 거래 상태 {TRADE_STATUS_LABELS[trade.tradeStatus] ?? trade.tradeStatus}
        </p>
        <p className="text-base-content/70">
          판매자 {trade.sellerNickname} · 낙찰자 {trade.buyerNickname}
        </p>
        <p className="mt-1 text-xs text-base-content/60">
          발송 기한 {formatDateTime(trade.schedule.shipDueAt)} · 구매 확정 기한 {formatDateTime(trade.schedule.confirmDueAt)}
          {trade.schedule.isUnfulfilled && ' · 미이행 표시'}
        </p>
      </section>

      <section className="mb-4 text-sm">
        <h3 className="mb-1 font-medium">배송 정보</h3>
        {delivery ? (
          <div className="rounded-box border border-base-300 p-3">
            <p>
              {delivery.receiverName} · {delivery.receiverPhone}
            </p>
            <p className="break-words">
              ({delivery.zipCode}) {delivery.address} {delivery.addressDetail}
            </p>
            {delivery.deliveryMessage && <p className="break-words text-base-content/70">요청: {delivery.deliveryMessage}</p>}
            <p className="mt-1 text-base-content/70">
              {delivery.trackingNumber ? `${delivery.courierName} ${delivery.trackingNumber} (${formatDateTime(delivery.shippedAt)} 발송)` : '발송 등록 전'}
            </p>
          </div>
        ) : (
          <p className="text-base-content/60">낙찰자가 배송지를 입력하지 않았습니다.</p>
        )}
      </section>

      <section className="mb-4 text-sm">
        <h3 className="mb-1 font-medium">분쟁 이력 (최근 순)</h3>
        <ul className="flex flex-col gap-2">
          {trade.disputes.map((d) => (
            <li key={d.disputeId} className={`rounded-box border p-3 ${d.disputeId === item.disputeId ? 'border-primary' : 'border-base-300'}`}>
              <div className="mb-1 flex flex-wrap items-center gap-2">
                <span className={`badge badge-sm ${disputeStateBadge(d.disputeStatus, d.judgment)}`}>{disputeStateLabel(d.disputeStatus, d.judgment)}</span>
                <span className="text-xs text-base-content/60">
                  {DISPUTE_REPORTER_LABELS[d.reporterRole]} {d.reporterNickname} · 접수 직전 {TRADE_STATUS_LABELS[d.previousTradeStatus]} · {formatDateTime(d.createdAt)}
                </span>
                {d.disputeId === item.disputeId && <span className="badge badge-sm badge-primary">이 분쟁</span>}
              </div>
              <p className="whitespace-pre-wrap break-words">사유: {d.reason}</p>
              {d.judgment && (
                <p className="mt-1 whitespace-pre-wrap break-words text-base-content/80">
                  판정 {DISPUTE_JUDGMENT_LABELS[d.judgment]}
                  {d.judgedAt && ` (${formatDateTime(d.judgedAt)})`}: {d.judgmentReason}
                </p>
              )}
            </li>
          ))}
        </ul>
      </section>

      {!judgeable && <p className="rounded-box bg-base-200 p-3 text-sm">이 분쟁은 판정이 끝났거나 판정할 수 없는 상태입니다.</p>}
      {judgeable && (
        <section>
          <fieldset className="mb-3">
            <legend className="mb-1 text-sm font-medium">판정</legend>
            <div className="flex flex-col gap-2">
              {JUDGMENTS.map((value) => {
                const disabled = value === 'PAYOUT' && !payoutAllowed;
                return (
                  <label key={value} className={`flex items-start gap-2 text-sm ${disabled ? 'opacity-60' : 'cursor-pointer'}`}>
                    <input
                      type="radio"
                      name="dispute-judgment"
                      className="radio radio-sm mt-0.5"
                      value={value}
                      disabled={disabled}
                      checked={judgment === value}
                      onChange={() => {
                        setJudgment(value);
                        setConfirmed(false);
                      }}
                    />
                    <span>
                      <span className="font-medium">{DISPUTE_JUDGMENT_LABELS[value]}</span>
                      {disabled && <span className="block text-xs text-error">발송이 등록되지 않은 거래는 지급으로 판정할 수 없습니다.</span>}
                      {judgment === value && <span className="block text-xs text-base-content/70">{describe(value)}</span>}
                    </span>
                  </label>
                );
              })}
            </div>
          </fieldset>

          {MONEY_JUDGMENTS.includes(judgment) && (
            <label className="mb-3 flex cursor-pointer items-center gap-2 rounded-box border border-warning bg-warning/10 p-3 text-sm">
              <input type="checkbox" className="checkbox checkbox-sm" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />
              <span>위 내용으로 포인트를 처리하는 것을 확인했습니다.</span>
            </label>
          )}

          <label htmlFor="judgment-reason" className="mb-1 block text-sm font-medium">
            판정 사유
          </label>
          <textarea
            id="judgment-reason"
            className="textarea textarea-bordered w-full"
            rows={3}
            value={reason}
            required
            aria-invalid={reason !== '' && reasonProblem !== null}
            onChange={(event) => setReason(event.target.value)}
            aria-describedby={problem ? 'judgment-reason-count judgment-problem' : 'judgment-reason-count'}
          />
          <p id="judgment-reason-count" className={`mt-1 text-xs ${trimmedReason.length > MAX_REASON ? 'text-error' : 'text-base-content/60'}`}>
            {trimmedReason.length} / {MAX_REASON}
          </p>
          {problem && (
            <p id="judgment-problem" role={reason !== '' ? 'status' : undefined} className="mt-3 text-sm text-base-content/70">
              {problem}
            </p>
          )}
          <button
            type="button"
            className="btn btn-error mt-3"
            disabled={problem !== null || submitting}
            aria-describedby={problem ? 'judgment-problem' : undefined}
            onClick={submit}
          >
            판정하기
          </button>
        </section>
      )}
    </>
  );
}

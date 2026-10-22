import { useState } from 'react';
import { getErrorMessage } from '../api/apiResponse';
import { openDispute } from '../api/tradeApi';
import { useToast } from '../hooks/useToast';
import { formatDateTime } from '../utils/format';
import Modal from './Modal';

const MAX_REASON = 500;

const JUDGMENT_LABELS = {
  REFUND: '반환(거래 취소, 예약 포인트 반환)',
  PAYOUT: '지급(거래 완료, 판매자에게 지급)',
  RESUME: '거래 진행(거래를 이어서 진행)',
  HOLD: '보류 유지',
};

// 분쟁 (화면 설계서 3-5, 기능 명세서 3-9 분쟁 접수). 버튼은 서버가 내려주는 availableActions로 만든다.
// - OPEN_DISPUTE: 사유를 적어 분쟁을 접수한다. 접수하면 거래가 분쟁 상태가 되어 관리자 판정을 기다린다.
// 한 거래에서 당사자별로 3회까지 접수할 수 있고, 가장 최근 분쟁은 판정 여부와 관계없이 보여 준다.
// 처리가 끝나면 onChanged로 거래를 다시 받는다.
export default function TradeDisputePanel({ trade, onChanged }) {
  const toast = useToast();
  const { dispute, availableActions: actions } = trade;
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const canOpen = actions.includes('OPEN_DISPUTE');
  // 앞뒤 공백(NBSP·전각 공백 포함)은 서버와 같이 지우고 센다.
  const trimmedReason = reason.replace(/^[\p{Z}\s\u200B-\u200D\u2060\uFEFF]+|[\p{Z}\s\u200B-\u200D\u2060\uFEFF]+$/gu, '');
  const reasonProblem =
    trimmedReason === '' ? '분쟁 사유를 입력해 주세요.' : trimmedReason.length > MAX_REASON ? `분쟁 사유는 ${MAX_REASON}자 이하로 입력해 주세요.` : null;
  // 준비·진행 상태인데 접수할 수 없으면 이 거래에서 접수할 수 있는 횟수를 모두 쓴 것이다.
  const limitReached = !canOpen && (trade.tradeStatus === 'READY' || trade.tradeStatus === 'IN_PROGRESS');

  if (!dispute && !canOpen && !limitReached) {
    return null;
  }

  // 제출 중에는 닫지 않는다. 닫을 때는 사유를 비우지만, 오류가 났을 때는 다시 쓰지 않도록 사유를 남긴다.
  function close(keepReason = false) {
    setOpen(false);
    if (!keepReason) {
      setReason('');
    }
  }
  async function submit() {
    setSubmitting(true);
    try {
      await openDispute(trade.tradeId, trimmedReason);
      toast.success('분쟁을 접수했습니다. 관리자 판정을 기다려 주세요.');
      close();
      onChanged();
    } catch (error) {
      close(true);
      toast.error(getErrorMessage(error));
      onChanged();   // 상대방이 먼저 접수했거나 거래 상태가 바뀌었을 수 있으므로 다시 받는다.
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mt-4 rounded-box border border-base-300 p-4">
      <h2 className="mb-2 font-bold">분쟁</h2>

      {dispute && (
        <div className="mb-3 rounded bg-base-200 p-3 text-sm">
          <p className="font-medium">
            {dispute.disputeStatus === 'OPEN'
              ? `${dispute.isMine ? '내가' : '상대방이'} 분쟁을 접수했습니다. 관리자 판정을 기다리는 중입니다.`
              : `${dispute.isMine ? '내가' : '상대방이'} 접수한 분쟁이 판정되었습니다.`}
          </p>
          <p className="mt-1 whitespace-pre-wrap text-base-content/80">사유: {dispute.reason}</p>
          <p className="mt-1 text-base-content/60">접수 {formatDateTime(dispute.createdAt)}</p>
          {dispute.disputeStatus === 'JUDGED' && (
            <p className="mt-1">
              판정: {JUDGMENT_LABELS[dispute.judgment] ?? dispute.judgment}
              {dispute.judgedAt && ` (${formatDateTime(dispute.judgedAt)})`}
            </p>
          )}
        </div>
      )}

      {canOpen && (
        <button type="button" className="btn btn-outline btn-error w-full" onClick={() => setOpen(true)}>
          분쟁 접수
        </button>
      )}
      {limitReached && <p className="text-sm text-base-content/70">이 거래에서 접수할 수 있는 분쟁 횟수(3회)를 모두 사용했습니다.</p>}
      {(canOpen || limitReached) && (
        <p className="mt-2 text-xs text-base-content/60">
          분쟁을 접수하면 거래가 분쟁 상태가 되어 관리자가 판정할 때까지 거래 상태를 바꿀 수 없고 자동 구매 확정도 중단됩니다. 분쟁은 한 거래에서
          3회까지 접수할 수 있습니다.
        </p>
      )}

      <Modal
        open={open}
        title="분쟁을 접수할까요?"
        onClose={() => {
          if (!submitting) close();
        }}
      >
        <p className="mb-3 rounded bg-warning/20 p-3 text-sm">
          접수하면 처리 중인 취소 요청은 종료되고, 관리자 판정이 나올 때까지 배송지 입력·발송 등록·구매 확정·취소 요청을 할 수 없습니다.
        </p>
        <label htmlFor="dispute-reason" className="mb-1 block text-sm font-medium">
          분쟁 사유
        </label>
        <textarea
          id="dispute-reason"
          className="textarea textarea-bordered w-full"
          rows={4}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="분쟁을 접수하는 사유를 적어 주세요."
        />
        <p className={`mt-1 text-sm ${reasonProblem && reason !== '' ? 'text-error' : 'text-base-content/60'}`}>
          {reasonProblem && reason !== '' ? reasonProblem : `${reason.length} / ${MAX_REASON}자`}
        </p>
        <button type="button" className="btn btn-error mt-3" disabled={submitting || reasonProblem !== null} onClick={submit}>
          {submitting ? '접수 중...' : '분쟁 접수'}
        </button>
      </Modal>
    </div>
  );
}

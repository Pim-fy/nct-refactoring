import { useState } from 'react';
import { getErrorMessage } from '../api/apiResponse';
import { acceptCancel, rejectCancel, requestCancel } from '../api/tradeApi';
import { useToast } from '../hooks/useToast';
import { TRADE_ROLE_LABELS } from '../utils/format';
import Modal from './Modal';

const MAX_REASON = 500;

// 거래 취소 (화면 설계서 3-5, 기능 명세서 3-8 거래 취소). 버튼은 서버가 내려주는 availableActions로 만든다.
// - REQUEST_CANCEL: 사유를 적어 취소를 요청한다. 양쪽이 합의해야 취소되고, 거절된 요청도 횟수에 포함되어 한 거래에서 3회까지 낼 수 있다.
// - ACCEPT_CANCEL·REJECT_CANCEL: 상대방이 요청한 취소에 응답한다. 수락하면 거래가 취소되고 낙찰자의 예약 포인트가 반환된다.
// 처리가 끝나면 onChanged로 거래를 다시 받는다.
export default function TradeCancelPanel({ trade, onChanged }) {
  const toast = useToast();
  const { cancelRequest, availableActions: actions, myRole } = trade;
  const [mode, setMode] = useState(null);   // 'request' | 'accept' | 'reject' | null (열려 있는 모달)
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const canRequest = actions.includes('REQUEST_CANCEL');
  const canRespond = actions.includes('ACCEPT_CANCEL') && actions.includes('REJECT_CANCEL');
  const mineWaiting = cancelRequest && cancelRequest.requesterRole === myRole;
  // 앞뒤 공백(NBSP·전각 공백 포함)은 서버와 같이 지우고 센다.
  const trimmedReason = reason.replace(/^[\p{Z}\s\u200B-\u200D\u2060\uFEFF]+|[\p{Z}\s\u200B-\u200D\u2060\uFEFF]+$/gu, '');
  const reasonProblem =
    trimmedReason === '' ? '취소 사유를 입력해 주세요.' : trimmedReason.length > MAX_REASON ? `취소 사유는 ${MAX_REASON}자 이하로 입력해 주세요.` : null;
  // 준비·진행 상태인데 요청할 수도 응답할 수도 없고 처리 중인 요청도 없으면, 이 거래에서 낼 수 있는 요청 횟수를 모두 쓴 것이다.
  const limitReached = !cancelRequest && !canRequest && (trade.tradeStatus === 'READY' || trade.tradeStatus === 'IN_PROGRESS');

  if (!cancelRequest && !canRequest && !limitReached) {
    return null;
  }

  // 제출 중에는 닫지 않는다. 닫을 때는 사유를 비우지만, 오류가 났을 때는 다시 쓰지 않도록 사유를 남긴다.
  function close(keepReason = false) {
    setMode(null);
    if (!keepReason) {
      setReason('');
    }
  }
  async function submit() {
    setSubmitting(true);
    try {
      if (mode === 'request') {
        await requestCancel(trade.tradeId, trimmedReason);
        toast.success('취소를 요청했습니다. 상대방의 응답을 기다려 주세요.');
      } else if (mode === 'accept') {
        await acceptCancel(trade.tradeId, cancelRequest.requestId);
        toast.success('취소를 수락했습니다. 거래가 취소되었습니다.');
      } else if (mode === 'reject') {
        await rejectCancel(trade.tradeId, cancelRequest.requestId);
        toast.success('취소 요청을 거절했습니다.');
      }
      close();
      onChanged();
    } catch (error) {
      close(true);
      toast.error(getErrorMessage(error));
      onChanged();   // 상대방이 먼저 처리했거나 거래 상태가 바뀌었을 수 있으므로 다시 받는다.
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mt-4 rounded-box border border-base-300 p-4">
      <h2 className="mb-2 font-bold">거래 취소</h2>

      {cancelRequest && (
        <div className="mb-3 rounded bg-base-200 p-3 text-sm">
          <p className="font-medium">
            {mineWaiting ? '내가' : `${TRADE_ROLE_LABELS[cancelRequest.requesterRole]}측이`} 취소를 요청했습니다.
          </p>
          <p className="mt-1 whitespace-pre-wrap text-base-content/80">사유: {cancelRequest.reason}</p>
          {mineWaiting && <p className="mt-1 text-base-content/60">상대방의 응답을 기다리는 중입니다.</p>}
        </div>
      )}

      {canRespond && (
        <div className="flex gap-2">
          <button type="button" className="btn btn-primary flex-1" onClick={() => setMode('accept')}>
            취소 수락
          </button>
          <button type="button" className="btn flex-1" onClick={() => setMode('reject')}>
            취소 거절
          </button>
        </div>
      )}
      {canRequest && (
        <button type="button" className="btn btn-outline w-full" onClick={() => setMode('request')}>
          거래 취소 요청
        </button>
      )}
      {limitReached && (
        <p className="text-sm text-base-content/70">이 거래에서 낼 수 있는 취소 요청 횟수(3회)를 모두 사용했습니다. 상대방이 먼저 취소를 요청해야 합니다.</p>
      )}
      <p className="mt-2 text-xs text-base-content/60">양쪽이 합의해야 거래가 취소되고, 낙찰자의 예약 포인트가 반환됩니다. 취소 요청은 한 거래에서 3회까지 낼 수 있습니다.</p>

      <Modal
        open={mode === 'request'}
        title="거래 취소를 요청할까요?"
        onClose={() => {
          if (mode === 'request' && !submitting) close();
        }}
      >
        <label htmlFor="cancel-reason" className="mb-1 block text-sm font-medium">
          취소 사유
        </label>
        <textarea
          id="cancel-reason"
          className="textarea textarea-bordered w-full"
          rows={4}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="취소하려는 사유를 적어 주세요."
        />
        <p className={`mt-1 text-sm ${reasonProblem && reason !== '' ? 'text-error' : 'text-base-content/60'}`}>
          {reasonProblem && reason !== '' ? reasonProblem : `${reason.length} / ${MAX_REASON}자`}
        </p>
        <button type="button" className="btn btn-primary mt-3" disabled={submitting || reasonProblem !== null} onClick={submit}>
          {submitting ? '요청 중...' : '취소 요청'}
        </button>
      </Modal>

      <Modal
        open={mode === 'accept'}
        title="취소를 수락할까요?"
        onClose={() => {
          if (mode === 'accept' && !submitting) close();
        }}
      >
        <p className="mb-4 rounded bg-warning/20 p-3 text-sm">
          수락하면 거래가 취소되고 되돌릴 수 없습니다. 낙찰자의 예약 포인트는 반환됩니다.
        </p>
        <button type="button" className="btn btn-primary" disabled={submitting} onClick={submit}>
          {submitting ? '처리 중...' : '취소 수락'}
        </button>
      </Modal>

      <Modal
        open={mode === 'reject'}
        title="취소 요청을 거절할까요?"
        onClose={() => {
          if (mode === 'reject' && !submitting) close();
        }}
      >
        <p className="mb-4 text-sm">거절하면 요청이 종료되고 거래는 원래 상태로 이어집니다.</p>
        <button type="button" className="btn btn-primary" disabled={submitting} onClick={submit}>
          {submitting ? '처리 중...' : '취소 거절'}
        </button>
      </Modal>
    </div>
  );
}

import { useState } from 'react';
import { getErrorCode, getErrorMessage } from '../api/apiResponse';
import { stopAuction } from '../api/adminAuctionApi';
import { useToast } from '../hooks/useToast';
import { formatWon, isMeaningfulReason, trimReason } from '../utils/format';
import Modal from './Modal';

const MAX_REASON = 500;

// 경매 강제 종료·숨김 모달 (기능 명세서 3-10 경매 강제 종료·숨김). auction은 관리자 경매 목록의 항목이다.
// 중단하면 목록에서 숨기고 입찰을 받지 않으며, 최고 입찰자의 예약 포인트를 해제한다. 사유는 판매자와 입찰자에게 보인다.
// 열 때마다 새로 시작하도록 부모가 key를 경매 식별자로 준다.
export default function AuctionStopModal({ auction, onClose, onDone }) {
  const toast = useToast();
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const trimmedReason = trimReason(reason);
  const problem =
    trimmedReason.length > MAX_REASON
      ? `중단 사유는 ${MAX_REASON}자 이하로 입력해 주세요.`
      : !isMeaningfulReason(trimmedReason)
        ? '중단 사유를 입력해 주세요.'
        : null;

  async function submit() {
    setSubmitting(true);
    try {
      const result = await stopAuction(auction.auctionId, { stoppedReason: trimmedReason });
      toast.success(
        result.releasedReservationCount > 0 ? '경매를 중단하고 예약 포인트를 해제했습니다.' : '경매를 중단했습니다.',
      );
      onDone();
    } catch (error) {
      toast.error(getErrorMessage(error));
      const code = getErrorCode(error);
      // 이미 종료·중단되었거나 없는 경매면 되풀이해도 소용없으므로 닫고 목록을 새로 받는다.
      if (code === 'AUCTION_STATUS_INVALID' || code === 'AUCTION_NOT_FOUND') {
        onDone();
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal open={auction !== null} title="경매 중단" onClose={onClose}>
      {auction && (
        <>
          <div className="mb-4 rounded-box bg-base-200 p-3 text-sm">
            <p className="font-medium">{auction.productName}</p>
            <p className="text-base-content/70">
              판매자 {auction.sellerNickname} · 현재가 {formatWon(auction.currentPrice)} · 입찰 {auction.bidCount}건
            </p>
          </div>
          <p className="mb-3 text-sm text-base-content/70">
            중단하면 경매 목록에서 숨겨지고 입찰을 받지 않습니다. 최고 입찰자의 예약 포인트는 바로 해제되며, 되돌릴 수 없습니다.
            중단 사유는 판매자와 입찰자가 경매 상세에서 볼 수 있습니다.
          </p>
          <label htmlFor="stop-reason" className="mb-1 block text-sm font-medium">
            중단 사유
          </label>
          <textarea
            id="stop-reason"
            className="textarea textarea-bordered w-full"
            rows={3}
            value={reason}
            required
            aria-invalid={reason !== '' && problem !== null}
            onChange={(event) => setReason(event.target.value)}
            aria-describedby={problem ? 'stop-reason-count stop-problem' : 'stop-reason-count'}
          />
          <p id="stop-reason-count" className={`mt-1 text-xs ${trimmedReason.length > MAX_REASON ? 'text-error' : 'text-base-content/60'}`}>
            {trimmedReason.length} / {MAX_REASON}
          </p>
          {problem && (
            <p id="stop-problem" role={reason !== '' ? 'status' : undefined} className="mt-3 text-sm text-base-content/70">
              {problem}
            </p>
          )}
          <button
            type="button"
            className="btn btn-error mt-3"
            disabled={problem !== null || submitting}
            aria-describedby={problem ? 'stop-problem' : undefined}
            onClick={submit}
          >
            중단하기
          </button>
        </>
      )}
    </Modal>
  );
}

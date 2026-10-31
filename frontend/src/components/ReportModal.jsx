import { useState } from 'react';
import { getErrorCode, getErrorMessage } from '../api/apiResponse';
import { submitReport } from '../api/reportApi';
import { useToast } from '../hooks/useToast';
import { REPORT_REASON_LABELS, REPORT_TARGET_LABELS } from '../utils/format';
import Modal from './Modal';

const MAX_DETAIL = 500;
const DETAIL_REQUIRED = 'INAPPROPRIATE_OR_ETC';
const RETRY_USELESS = ['REPORT_ALREADY_SUBMITTED', 'REPORT_TARGET_NOT_FOUND', 'REPORT_SELF_NOT_ALLOWED'];

// 신고하기 모달 (화면 설계서 신고, 기능 명세서 3-9). 경매 상세·거래 상세에서 열고, 대상 유형과 식별자는 부모가 정한다.
// 사유는 4종 중 하나를 고르고, 부적절 행위·기타는 상세 내용이 필수다. 접수하면 onClose로 닫는다.
// 접수 중에는 닫지 않는다. 오류가 나면 입력을 남기고 모달을 열어 둬 다시 시도할 수 있게 한다.
export default function ReportModal({ open, targetType, targetId, targetLabel, onClose }) {
  const toast = useToast();
  const [reason, setReason] = useState('');
  const [detail, setDetail] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // 앞뒤 공백(NBSP·전각·제로폭·서식 문자와 한글 채움 문자 포함)은 서버와 같이 지우고 센다.
  const trimmedDetail = detail.replace(/^[\p{Z}\s\p{Cf}\u3164\u2800]+|[\p{Z}\s\p{Cf}\u3164\u2800]+$/gu, '');
  const detailRequired = reason === DETAIL_REQUIRED;
  const detailProblem =
    detailRequired && trimmedDetail === ''
      ? '부적절 행위·기타는 상세 내용을 입력해 주세요.'
      : trimmedDetail.length > MAX_DETAIL
        ? `상세 내용은 ${MAX_DETAIL}자 이하로 입력해 주세요.`
        : null;
  const canSubmit = reason !== '' && detailProblem === null && !submitting;

  function close() {
    setReason('');
    setDetail('');
    onClose();
  }

  async function submit() {
    setSubmitting(true);
    try {
      await submitReport({ targetType, targetId, reportReason: reason, detail: trimmedDetail === '' ? undefined : trimmedDetail });
      toast.success('신고를 접수했습니다. 처리 결과는 내 신고 내역에서 확인할 수 있습니다.');
      close();
    } catch (error) {
      toast.error(getErrorMessage(error));
      // 같은 결과가 되풀이될 오류(이미 접수, 없는 대상, 본인 대상)는 다시 시도해도 소용없으므로 닫는다.
      if (RETRY_USELESS.includes(getErrorCode(error))) {
        close();
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal
      open={open}
      title={`${REPORT_TARGET_LABELS[targetType] ?? ''} 신고하기`}
      onClose={() => {
        // 접수 중에 Esc·바깥 클릭으로 닫혀도 부모의 열림 상태는 맞춘다. 입력은 지우지 않아 실패하면 다시 열어 이어 쓸 수 있다.
        if (submitting) onClose();
        else close();
      }}
    >
      {targetLabel && <p className="mb-3 text-sm text-base-content/70">대상: {targetLabel}</p>}
      <fieldset className="mb-3">
        <legend className="mb-1 text-sm font-medium">신고 사유</legend>
        <div className="flex flex-col gap-1">
          {Object.entries(REPORT_REASON_LABELS).map(([value, label]) => (
            <label key={value} className="flex cursor-pointer items-center gap-2 text-sm">
              <input type="radio" name="report-reason" className="radio radio-sm" value={value} checked={reason === value} onChange={() => setReason(value)} />
              {label}
            </label>
          ))}
        </div>
      </fieldset>
      <label htmlFor="report-detail" className="mb-1 block text-sm font-medium">
        상세 내용 {detailRequired ? '(필수)' : '(선택)'}
      </label>
      <textarea
        id="report-detail"
        className="textarea textarea-bordered w-full"
        rows={4}
        value={detail}
        onChange={(event) => setDetail(event.target.value)}
        placeholder="신고하는 이유를 자세히 적어 주세요."
      />
      <p className={`mt-1 text-sm ${detailProblem && detail !== '' ? 'text-error' : 'text-base-content/60'}`}>
        {detailProblem && detail !== '' ? detailProblem : `${detail.length} / ${MAX_DETAIL}자`}
      </p>
      <p className="mt-2 text-xs text-base-content/60">같은 대상은 처리되기 전까지 한 번만 신고할 수 있습니다.</p>
      <button type="button" className="btn btn-error mt-3" disabled={!canSubmit} onClick={submit}>
        {submitting ? '접수 중...' : '신고 접수'}
      </button>
    </Modal>
  );
}

import { useState } from 'react';
import { getErrorCode, getErrorMessage } from '../api/apiResponse';
import { processReport } from '../api/adminReportApi';
import { useToast } from '../hooks/useToast';
import {
  isMeaningfulReason,
  MEMBER_STATUS_LABELS,
  REPORT_ACTION_LABELS,
  REPORT_REASON_LABELS,
  REPORT_TARGET_LABELS,
  trimReason,
} from '../utils/format';
import Modal from './Modal';

const MAX_REASON = 500;
const MAX_DAYS = 3650; // 서버 상한(10년)보다 조금 안쪽에서 막는다.
const LEVELS = ['RESTRICTED_TRADE', 'RESTRICTED_LOGIN'];
// 이용 제한을 걸 회원은 서버가 신고 대상 유형으로 정한다.
const RESTRICT_TARGETS = {
  MEMBER: '신고된 회원',
  AUCTION: '경매의 판매자',
  TRADE: '신고자의 거래 상대',
};
// 이미 처리된 신고이거나 없는 신고면 되풀이해도 소용없으므로 닫고 목록을 새로 받는다.
const CLOSE_CODES = ['REPORT_ALREADY_PROCESSED', 'REPORT_NOT_FOUND'];

// 한국 시간 기준 "YYYY-MM-DDTHH:mm" (datetime-local 값 형식). 열 때 한 번 계산해 입력의 최소·최대로 쓴다.
function kstLocal(epochMs) {
  return new Date(epochMs + 9 * 60 * 60 * 1000).toISOString().slice(0, 16);
}

// 성공한 처리를 알리는 문구. 조치 대상이 이미 그 상태였으면 조치 없이 신고만 처리했다고 알린다.
function resultMessage(result) {
  const applied = result.appliedAction;
  if (result.actionType === 'REJECTED') return '신고를 반려했습니다.';
  if (result.actionType === 'AUCTION_STOPPED') {
    return applied.isAlreadyApplied ? '이미 중단된 경매라 신고만 처리 완료로 남겼습니다.' : '경매를 중단하고 신고를 처리했습니다.';
  }
  return applied.isAlreadyApplied
    ? `이미 ${MEMBER_STATUS_LABELS[applied.memberStatus] ?? '제한'} 상태인 회원이라 신고만 처리 완료로 남겼습니다.`
    : '회원 이용을 제한하고 신고를 처리했습니다.';
}

// 신고 처리 모달 (기능 명세서 3-10 신고 처리). report는 관리자 신고 목록의 항목이다.
// 경매 중단과 이용 제한은 같은 요청에서 함께 수행된다. 경매 중단은 경매 신고에만 있고, 이용 제한의 대상은 서버가 정한다.
// 열 때마다 새로 시작하도록 부모가 key를 신고 식별자로 준다.
export default function ReportProcessModal({ report, onClose, onDone }) {
  const toast = useToast();
  const actions = report?.targetType === 'AUCTION' ? ['AUCTION_STOPPED', 'MEMBER_RESTRICTED', 'REJECTED'] : ['MEMBER_RESTRICTED', 'REJECTED'];

  const [actionType, setActionType] = useState('');
  const [level, setLevel] = useState('RESTRICTED_TRADE');
  const [permanent, setPermanent] = useState(false);
  const [expiresLocal, setExpiresLocal] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [bounds] = useState(() => {
    const now = Date.now();
    return { min: kstLocal(now), max: kstLocal(now + MAX_DAYS * 24 * 60 * 60 * 1000) };
  });

  const restrict = actionType === 'MEMBER_RESTRICTED';
  const trimmedReason = trimReason(reason);
  const actionProblem = actionType === '' ? '처리 결과를 선택해 주세요.' : null;
  const reasonProblem =
    trimmedReason.length > MAX_REASON
      ? `처리 사유는 ${MAX_REASON}자 이하로 입력해 주세요.`
      : !isMeaningfulReason(trimmedReason)
        ? '처리 사유를 입력해 주세요.'
        : null;
  const expiresProblem =
    !restrict || permanent
      ? null
      : expiresLocal === ''
        ? '만료 시각을 입력해 주세요.'
        : expiresLocal <= bounds.min
          ? '만료 시각은 지금보다 뒤로 입력해 주세요.'
          : expiresLocal > bounds.max
            ? '만료 시각은 10년 안으로 입력해 주세요. 더 길게 막으려면 영구 제한을 선택해 주세요.'
            : null;
  const problem = actionProblem ?? expiresProblem ?? reasonProblem;
  const canSubmit = problem === null && !submitting;

  async function submit() {
    // 한국 시간 기준으로 입력받는다. 지금보다 뒤여야 한다.
    const expiresAt = restrict && !permanent ? `${expiresLocal}:00+09:00` : undefined;
    if (expiresAt && !(new Date(expiresAt).getTime() > Date.now())) {
      toast.error('만료 시각은 지금보다 뒤로 입력해 주세요.');
      return;
    }
    setSubmitting(true);
    try {
      const result = await processReport(report.reportId, {
        actionType,
        actionReason: trimmedReason,
        restriction: restrict ? { restrictionLevel: level, isPermanent: permanent, expiresAt } : undefined,
      });
      toast.success(resultMessage(result));
      onDone();
    } catch (error) {
      toast.error(getErrorMessage(error));
      if (CLOSE_CODES.includes(getErrorCode(error))) {
        onDone();
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal open={report !== null} title="신고 처리" onClose={onClose}>
      {report && (
        <>
          <div className="mb-4 rounded-box bg-base-200 p-3 text-sm">
            <p className="font-medium break-words">
              [{REPORT_TARGET_LABELS[report.targetType]}] {report.targetLabel}
            </p>
            <p className="text-base-content/70">
              신고자 {report.reporterNickname} · {REPORT_REASON_LABELS[report.reportReason] ?? report.reportReason}
            </p>
            {report.detail && <p className="mt-1 break-words text-base-content/70">{report.detail}</p>}
          </div>

          <fieldset className="mb-3">
            <legend className="mb-1 text-sm font-medium">처리 결과</legend>
            <div className="flex flex-col gap-2">
              {actions.map((value) => (
                <label key={value} className="flex cursor-pointer items-start gap-2 text-sm">
                  <input type="radio" name="report-action" className="radio radio-sm mt-0.5" value={value} checked={actionType === value} onChange={() => setActionType(value)} />
                  <span>
                    <span className="font-medium">{REPORT_ACTION_LABELS[value]}</span>
                    <span className="block text-xs text-base-content/60">
                      {value === 'AUCTION_STOPPED' && '경매를 중단해 목록에서 숨기고 최고 입찰자의 예약 포인트를 해제합니다. 되돌릴 수 없습니다.'}
                      {value === 'MEMBER_RESTRICTED' && `${RESTRICT_TARGETS[report.targetType]}의 이용을 제한합니다. 이미 제한된 회원이면 제한은 바꾸지 않고 신고만 처리합니다.`}
                      {value === 'REJECTED' && '아무 조치 없이 신고를 반려합니다. 반려한 신고는 다시 처리하지 않습니다.'}
                    </span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>

          {restrict && (
            <>
              <fieldset className="mb-3">
                <legend className="mb-1 text-sm font-medium">제한 수준</legend>
                <div className="flex flex-col gap-2">
                  {LEVELS.map((value) => (
                    <label key={value} className="flex cursor-pointer items-center gap-2 text-sm">
                      <input type="radio" name="report-level" className="radio radio-sm" value={value} checked={level === value} onChange={() => setLevel(value)} />
                      {MEMBER_STATUS_LABELS[value]}
                    </label>
                  ))}
                </div>
              </fieldset>
              <fieldset className="mb-3">
                <legend className="mb-1 text-sm font-medium">기간</legend>
                <label className="mb-2 flex cursor-pointer items-center gap-2 text-sm">
                  <input type="checkbox" className="checkbox checkbox-sm" checked={permanent} onChange={(event) => setPermanent(event.target.checked)} />
                  영구 제한
                </label>
                {!permanent && (
                  <div>
                    <label htmlFor="report-expires" className="mb-1 block text-xs text-base-content/70">
                      만료 시각 (한국 시간, 최대 10년)
                    </label>
                    <input
                      id="report-expires"
                      type="datetime-local"
                      className="input input-bordered input-sm"
                      value={expiresLocal}
                      min={bounds.min}
                      max={bounds.max}
                      required
                      aria-invalid={expiresLocal !== '' && expiresProblem !== null}
                      onChange={(event) => setExpiresLocal(event.target.value)}
                    />
                  </div>
                )}
              </fieldset>
            </>
          )}

          <label htmlFor="report-process-reason" className="mb-1 block text-sm font-medium">
            처리 사유
          </label>
          <textarea
            id="report-process-reason"
            className="textarea textarea-bordered w-full"
            rows={3}
            value={reason}
            required
            aria-invalid={reason !== '' && reasonProblem !== null}
            onChange={(event) => setReason(event.target.value)}
            aria-describedby={reasonProblem ? 'report-process-count report-process-problem' : 'report-process-count'}
          />
          <p id="report-process-count" className={`mt-1 text-xs ${trimmedReason.length > MAX_REASON ? 'text-error' : 'text-base-content/60'}`}>
            {trimmedReason.length} / {MAX_REASON}
          </p>
          {restrict && <p className="mt-1 text-xs text-base-content/60">처리 사유는 이용 제한 사유로도 남습니다.</p>}

          {problem && (
            <p id="report-process-problem" role={reason !== '' ? 'status' : undefined} className="mt-3 text-sm text-base-content/70">
              {problem}
            </p>
          )}
          <button type="button" className="btn btn-primary mt-3" disabled={!canSubmit} aria-describedby={problem ? 'report-process-problem' : undefined} onClick={submit}>
            처리하기
          </button>
        </>
      )}
    </Modal>
  );
}

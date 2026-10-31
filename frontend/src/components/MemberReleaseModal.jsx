import { useState } from 'react';
import { getErrorCode, getErrorMessage } from '../api/apiResponse';
import { releaseMember } from '../api/adminMemberApi';
import { useToast } from '../hooks/useToast';
import { formatRestrictionPeriod, isMeaningfulReason, MEMBER_STATUS_LABELS, trimReason } from '../utils/format';
import Modal from './Modal';

const MAX_REASON = 500;

// 회원 이용 제한 해제 모달 (기능 명세서 3-10 회원 이용 제한). member는 관리자 회원 목록의 항목이다.
// 해제하면 정상으로 돌아가고, 해제 사유가 이력에 남는다. 요청에는 화면에 보인 현재 제한의 식별자를 실어 보내며,
// 그사이 다른 관리자가 바꿨으면 서버가 409로 거부하고 목록을 다시 받아 바뀐 제한을 보여 준다.
// 열 때마다 새로 시작하도록 부모가 key를 회원 식별자로 준다.
export default function MemberReleaseModal({ member, onClose, onDone, onStale }) {
  const toast = useToast();
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const current = member?.restriction ?? null;
  const trimmedReason = trimReason(reason);
  const problem =
    trimmedReason.length > MAX_REASON
      ? `해제 사유는 ${MAX_REASON}자 이하로 입력해 주세요.`
      : !isMeaningfulReason(trimmedReason)
        ? '해제 사유를 입력해 주세요.'
        : null;

  async function submit() {
    setSubmitting(true);
    try {
      await releaseMember(member.memberId, { restrictionId: current?.restrictionId, reason: trimmedReason });
      toast.success('이용 제한을 해제했습니다.');
      onDone();
    } catch (error) {
      toast.error(getErrorMessage(error));
      const code = getErrorCode(error);
      if (code === 'MEMBER_RESTRICTION_CHANGED') {
        onStale(); // 그사이 제한이 바뀌었다. 목록을 다시 받아 바뀐 제한을 보여 주고 다시 확인받는다.
      } else if (code === 'MEMBER_NOT_RESTRICTED' || code === 'MEMBER_NOT_FOUND' || code === 'MEMBER_ALREADY_WITHDRAWN') {
        // 이미 해제되었거나 대상이 아니면 되풀이해도 소용없으므로 닫고 목록을 새로 받는다.
        onDone();
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal open={member !== null} title="이용 제한 해제" onClose={onClose}>
      {member && (
        <>
          <p className="mb-3 text-sm text-base-content/70">
            대상: {member.nickname} ({member.loginId})
          </p>
          {current && (
            <div className="mb-4 rounded-box bg-base-200 p-3 text-sm">
              <p>
                현재: {MEMBER_STATUS_LABELS[current.restrictionLevel]} · {formatRestrictionPeriod(current)}
              </p>
              <p className="break-words">사유: {current.reason}</p>
            </div>
          )}
          <label htmlFor="release-reason" className="mb-1 block text-sm font-medium">
            해제 사유
          </label>
          <textarea
            id="release-reason"
            className="textarea textarea-bordered w-full"
            rows={3}
            value={reason}
            required
            aria-invalid={reason !== '' && problem !== null}
            onChange={(event) => setReason(event.target.value)}
            aria-describedby={problem ? 'release-reason-count release-problem' : 'release-reason-count'}
          />
          <p id="release-reason-count" className={`mt-1 text-xs ${trimmedReason.length > MAX_REASON ? 'text-error' : 'text-base-content/60'}`}>
            {trimmedReason.length} / {MAX_REASON}
          </p>
          {problem && (
            <p id="release-problem" role={reason !== '' ? 'status' : undefined} className="mt-3 text-sm text-base-content/70">
              {problem}
            </p>
          )}
          <button
            type="button"
            className="btn btn-primary mt-3"
            disabled={problem !== null || submitting}
            aria-describedby={problem ? 'release-problem' : undefined}
            onClick={submit}
          >
            해제하기
          </button>
        </>
      )}
    </Modal>
  );
}

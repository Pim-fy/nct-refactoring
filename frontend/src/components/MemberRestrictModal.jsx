import { useState } from 'react';
import { getErrorCode, getErrorMessage } from '../api/apiResponse';
import { restrictMember } from '../api/adminMemberApi';
import { useToast } from '../hooks/useToast';
import { formatRestrictionPeriod, isMeaningfulReason, MEMBER_STATUS_LABELS, trimReason } from '../utils/format';
import Modal from './Modal';

const MAX_REASON = 500;
const MAX_DAYS = 3650; // 서버 상한(10년)보다 조금 안쪽에서 막는다.
const LEVELS = ['RESTRICTED_TRADE', 'RESTRICTED_LOGIN'];
const LEVEL_HINTS = {
  RESTRICTED_TRADE: '로그인과 조회는 되고, 경매 등록·입찰·포인트 충전은 할 수 없습니다. 진행 중인 거래는 마무리할 수 있습니다.',
  RESTRICTED_LOGIN: '로그인할 수 없고, 이미 로그인한 요청도 다음 요청부터 막힙니다.',
};
// 그사이 현재 제한이 바뀌었을 때 서버가 돌려주는 코드. 목록을 다시 받아 현재 제한을 보여 주고 교체를 다시 확인받는다.
const STALE_CODES = ['MEMBER_ALREADY_RESTRICTED', 'MEMBER_RESTRICTION_CHANGED'];

// 한국 시간 기준 "YYYY-MM-DDTHH:mm" (datetime-local 값 형식). 열 때 한 번 계산해 입력의 최소·최대로 쓴다.
function kstLocal(epochMs) {
  return new Date(epochMs + 9 * 60 * 60 * 1000).toISOString().slice(0, 16);
}

// 회원 이용 제한 모달 (기능 명세서 3-10 회원 이용 제한). member는 관리자 회원 목록의 항목이다.
// 제한은 겹쳐 걸 수 없어 이미 제한 중인 회원에게는 항상 기존 제한이 새 제한으로 교체된다.
// 이때 현재 제한과 바뀌는 내용을 보여 주고, 교체를 확인해야 요청한다. 요청에는 확인한 현재 제한의 식별자를 실어 보내며,
// 그사이 다른 관리자가 바꿨으면 서버가 409로 거부한다. 열 때마다 새로 시작하도록 부모가 key를 회원 식별자로 준다.
export default function MemberRestrictModal({ member, onClose, onDone, onStale }) {
  const toast = useToast();
  const current = member?.restriction ?? null;
  const restricted = current !== null;

  const [level, setLevel] = useState(current?.restrictionLevel ?? 'RESTRICTED_TRADE');
  const [permanent, setPermanent] = useState(false);
  const [expiresLocal, setExpiresLocal] = useState('');
  const [reason, setReason] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [bounds] = useState(() => {
    const now = Date.now();
    return { min: kstLocal(now), max: kstLocal(now + MAX_DAYS * 24 * 60 * 60 * 1000) };
  });

  const trimmedReason = trimReason(reason);
  const reasonProblem =
    trimmedReason.length > MAX_REASON
      ? `제한 사유는 ${MAX_REASON}자 이하로 입력해 주세요.`
      : !isMeaningfulReason(trimmedReason)
        ? '제한 사유를 입력해 주세요.'
        : null;
  const expiresProblem = permanent
    ? null
    : expiresLocal === ''
      ? '만료 시각을 입력해 주세요.'
      : expiresLocal <= bounds.min
        ? '만료 시각은 지금보다 뒤로 입력해 주세요.'
        : expiresLocal > bounds.max
          ? '만료 시각은 10년 안으로 입력해 주세요. 더 길게 막으려면 영구 제한을 선택해 주세요.'
          : null;
  const confirmProblem = restricted && !confirmed ? '현재 제한을 교체하려면 위 확인란에 체크해 주세요.' : null;
  const problem = reasonProblem ?? expiresProblem ?? confirmProblem;
  const canSubmit = problem === null && !submitting;

  async function submit() {
    // 한국 시간 기준으로 입력받는다. 지금보다 뒤여야 한다.
    const expiresAt = permanent ? undefined : `${expiresLocal}:00+09:00`;
    if (!permanent && !(new Date(expiresAt).getTime() > Date.now())) {
      toast.error('만료 시각은 지금보다 뒤로 입력해 주세요.');
      return;
    }
    setSubmitting(true);
    try {
      await restrictMember(member.memberId, {
        restrictionLevel: level,
        isPermanent: permanent,
        expiresAt,
        reason: trimmedReason,
        expectedRestrictionId: current?.restrictionId,
      });
      toast.success(restricted ? '이용 제한을 교체했습니다.' : '이용 제한을 적용했습니다.');
      onDone();
    } catch (error) {
      toast.error(getErrorMessage(error));
      const code = getErrorCode(error);
      if (STALE_CODES.includes(code)) {
        onStale(); // 그사이 다른 관리자가 제한을 걸거나 바꿨다. 목록을 다시 받아 현재 제한을 보여 주고 교체를 다시 확인받는다.
        setConfirmed(false);
      } else if (code === 'MEMBER_NOT_FOUND' || code === 'MEMBER_ALREADY_WITHDRAWN') {
        onDone();
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal open={member !== null} title="이용 제한" onClose={onClose}>
      {member && (
        <>
          <p className="mb-3 text-sm text-base-content/70">
            대상: {member.nickname} ({member.loginId})
          </p>

          {restricted && (
            <div role="alert" className="mb-4 rounded-box border border-warning bg-warning/10 p-3 text-sm">
              <p className="mb-1 font-medium">이미 이용이 제한된 회원입니다. 새로 걸면 현재 제한이 교체됩니다.</p>
              <p>
                현재: {MEMBER_STATUS_LABELS[current.restrictionLevel]} · {formatRestrictionPeriod(current)}
              </p>
              <p className="break-words">사유: {current.reason}</p>
              <p className="mt-1 font-medium">
                {level === current.restrictionLevel
                  ? `변경: 같은 수준(${MEMBER_STATUS_LABELS[level]})으로 기간·사유를 교체`
                  : `변경: ${MEMBER_STATUS_LABELS[current.restrictionLevel]} → ${MEMBER_STATUS_LABELS[level]}`}
              </p>
              <label className="mt-2 flex cursor-pointer items-center gap-2">
                <input type="checkbox" className="checkbox checkbox-sm" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />
                <span>현재 제한을 이 내용으로 교체하는 것을 확인했습니다.</span>
              </label>
            </div>
          )}

          <fieldset className="mb-3">
            <legend className="mb-1 text-sm font-medium">제한 수준</legend>
            <div className="flex flex-col gap-2">
              {LEVELS.map((value) => (
                <label key={value} className="flex cursor-pointer items-start gap-2 text-sm">
                  <input type="radio" name="restriction-level" className="radio radio-sm mt-0.5" value={value} checked={level === value} onChange={() => setLevel(value)} />
                  <span>
                    <span className="font-medium">{MEMBER_STATUS_LABELS[value]}</span>
                    <span className="block text-xs text-base-content/60">{LEVEL_HINTS[value]}</span>
                  </span>
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
                <label htmlFor="restriction-expires" className="mb-1 block text-xs text-base-content/70">
                  만료 시각 (한국 시간, 최대 10년)
                </label>
                <input
                  id="restriction-expires"
                  type="datetime-local"
                  className="input input-bordered input-sm"
                  value={expiresLocal}
                  min={bounds.min}
                  max={bounds.max}
                  required
                  aria-invalid={expiresLocal !== '' && expiresProblem !== null}
                  onChange={(event) => setExpiresLocal(event.target.value)}
                />
                <p className="mt-1 text-xs text-base-content/60">기간이 끝나도 관리자가 해제하기 전에는 제한이 유지됩니다.</p>
              </div>
            )}
          </fieldset>

          <label htmlFor="restriction-reason" className="mb-1 block text-sm font-medium">
            제한 사유
          </label>
          <textarea
            id="restriction-reason"
            className="textarea textarea-bordered w-full"
            rows={3}
            value={reason}
            required
            aria-invalid={reason !== '' && reasonProblem !== null}
            onChange={(event) => setReason(event.target.value)}
            aria-describedby="restriction-reason-count"
          />
          <p id="restriction-reason-count" className={`mt-1 text-xs ${trimmedReason.length > MAX_REASON ? 'text-error' : 'text-base-content/60'}`}>
            {trimmedReason.length} / {MAX_REASON}
          </p>

          {problem && (
            <p id="restriction-problem" role="status" className="mt-3 text-sm text-base-content/70">
              {problem}
            </p>
          )}
          <button type="button" className="btn btn-error mt-3" disabled={!canSubmit} aria-describedby={problem ? 'restriction-problem' : undefined} onClick={submit}>
            {restricted ? '교체하기' : '제한하기'}
          </button>
        </>
      )}
    </Modal>
  );
}

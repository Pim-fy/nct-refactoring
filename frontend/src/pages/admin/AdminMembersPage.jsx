import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { fetchAdminMembers } from '../../api/adminMemberApi';
import { getErrorMessage } from '../../api/apiResponse';
import MemberReleaseModal from '../../components/MemberReleaseModal';
import MemberRestrictModal from '../../components/MemberRestrictModal';
import Pagination from '../../components/Pagination';
import { useToast } from '../../hooks/useToast';
import { formatDateTime, formatRestrictionPeriod, MEMBER_STATUS_BADGES, MEMBER_STATUS_LABELS } from '../../utils/format';

const PAGE_SIZE = 20;
const STATUSES = Object.keys(MEMBER_STATUS_LABELS);

// 관리자 회원 관리 (기능 명세서 3-10 회원 이용 제한, 화면 설계서 관리자 회원 관리)
// - 목록: 상태·검색어(아이디·닉네임·이메일)로 거르고, 제한 중인 회원은 현재 제한(수준·기간·사유)을 보여 준다.
// - 제한: 정상 회원에게 건다. 이미 제한 중이면 현재 제한을 보여 주고 교체를 확인받는다.
// - 해제: 제한 중인 회원을 정상으로 되돌린다. 관리자 계정과 탈퇴 회원은 제한 대상이 아니다.
// 필터와 페이지는 주소창 쿼리에 보관한다.
export default function AdminMembersPage() {
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const page = Math.max(Number.parseInt(searchParams.get('page') ?? '0', 10) || 0, 0);
  const rawStatus = searchParams.get('status') ?? '';
  const status = STATUSES.includes(rawStatus) ? rawStatus : ''; // 주소창에 없는 상태가 있으면 전체로 본다.
  const keyword = searchParams.get('keyword') ?? '';

  const [reload, setReload] = useState(0);
  const [result, setResult] = useState({ key: null, data: null, failed: false });
  const [restrictId, setRestrictId] = useState(null);
  const [releaseId, setReleaseId] = useState(null);

  const queryKey = `${page}|${status}|${keyword}|${reload}`;

  useEffect(() => {
    let cancelled = false;
    fetchAdminMembers({ page, size: PAGE_SIZE, status: status || undefined, keyword: keyword || undefined })
      .then((data) => {
        if (cancelled) return;
        setResult({ key: queryKey, data, failed: false });
        // 새 목록에 모달 대상이 없으면(필터·페이지 때문에 빠졌거나 탈퇴 등) 대상을 비워, 나중에 모달이 불쑥 다시 열리지 않게 한다.
        const dropMissing = (id) => (id !== null && data.content.some((member) => member.memberId === id) ? id : null);
        setRestrictId(dropMissing);
        setReleaseId(dropMissing);
      })
      .catch((error) => {
        if (!cancelled) {
          setResult({ key: queryKey, data: null, failed: true });
          toast.error(getErrorMessage(error));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [page, status, keyword, queryKey, toast]);

  // 새로 받는 동안에도 이전 목록을 흐리게 보여 줘, 열려 있는 모달이 대상 회원을 잃지 않게 한다.
  const loading = result.key !== queryKey;
  const data = result.data;
  const restrictTarget = data?.content.find((member) => member.memberId === restrictId) ?? null;
  const releaseTarget = data?.content.find((member) => member.memberId === releaseId) ?? null;

  function updateQuery(changes) {
    const next = new URLSearchParams(searchParams);
    Object.entries(changes).forEach(([name, value]) => {
      if (value === '' || value === 0 || value === null) next.delete(name);
      else next.set(name, String(value));
    });
    setSearchParams(next);
  }

  function handleSearch(event) {
    event.preventDefault();
    updateQuery({ keyword: String(new FormData(event.currentTarget).get('keyword') ?? '').trim(), page: 0 });
  }

  function refresh() {
    setReload((count) => count + 1);
  }

  function finish() {
    setRestrictId(null);
    setReleaseId(null);
    refresh();
  }

  return (
    <div className="rounded-box bg-base-100 p-6 shadow-sm">
      <h1 className="mb-1 text-xl font-bold">회원 관리</h1>
      <p className="mb-6 text-sm text-base-content/60">
        이용 제한은 거래 제한과 로그인 정지 중 하나만 걸 수 있고, 이미 제한 중인 회원에게 다시 걸면 현재 제한이 교체됩니다. 기간이 끝나도 해제하기 전에는 제한이 유지됩니다.
      </p>

      <form onSubmit={handleSearch} className="mb-6 flex flex-wrap items-end gap-2">
        <label className="form-control">
          <span className="mb-1 block text-sm font-medium">상태</span>
          <select className="select select-bordered" value={status} onChange={(event) => updateQuery({ status: event.target.value, page: 0 })}>
            <option value="">전체</option>
            {Object.entries(MEMBER_STATUS_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className="form-control">
          <span className="mb-1 block text-sm font-medium">검색어</span>
          <input
            className="input input-bordered w-64"
            key={keyword}
            name="keyword"
            defaultValue={keyword}
            maxLength={100}
            placeholder="아이디·닉네임·이메일"
          />
        </label>
        <button type="submit" className="btn btn-primary">
          검색
        </button>
      </form>

      {result.key === null && (
        <div className="flex justify-center py-10">
          <span className="loading loading-spinner loading-lg text-primary" />
        </div>
      )}
      {result.key !== null && result.failed && !loading && <p className="py-10 text-center text-base-content/60">목록을 불러오지 못했습니다.</p>}
      {data !== null && (
        <div className={loading ? 'opacity-60' : undefined}>
          <div className="overflow-x-auto">
            <table className="table">
              <thead>
                <tr>
                  <th>회원</th>
                  <th>이메일</th>
                  <th className="w-28">상태</th>
                  <th>현재 제한</th>
                  <th className="w-36">가입일</th>
                  <th className="w-36">작업</th>
                </tr>
              </thead>
              <tbody>
                {data.content.length === 0 && (
                  <tr>
                    <td colSpan={6} className="py-8 text-center text-base-content/60">
                      조건에 맞는 회원이 없습니다.
                    </td>
                  </tr>
                )}
                {data.content.map((member) => {
                  const restricted = member.memberStatus === 'RESTRICTED_TRADE' || member.memberStatus === 'RESTRICTED_LOGIN';
                  const restrictable = member.memberRole !== 'ADMIN' && member.memberStatus !== 'WITHDRAWN';
                  return (
                    <tr key={member.memberId}>
                      <td>
                        <p className="font-medium">{member.nickname}</p>
                        <p className="text-xs text-base-content/60">
                          {member.loginId}
                          {member.memberRole === 'ADMIN' && ' · 관리자'}
                        </p>
                      </td>
                      <td className="break-all text-sm">{member.email}</td>
                      <td>
                        <span className={`badge badge-sm ${MEMBER_STATUS_BADGES[member.memberStatus] ?? 'badge-ghost'}`}>
                          {MEMBER_STATUS_LABELS[member.memberStatus] ?? member.memberStatus}
                        </span>
                      </td>
                      <td className="text-sm">
                        {member.restriction ? (
                          <>
                            <p>{formatRestrictionPeriod(member.restriction)}</p>
                            <p className="max-w-xs break-words text-xs text-base-content/60">{member.restriction.reason}</p>
                          </>
                        ) : (
                          '-'
                        )}
                      </td>
                      <td className="text-sm">{formatDateTime(member.createdAt)}</td>
                      <td>
                        {restrictable ? (
                          <div className="flex gap-2">
                            <button type="button" className="btn btn-sm btn-error btn-outline" onClick={() => setRestrictId(member.memberId)}>
                              {restricted ? '교체' : '제한'}
                            </button>
                            {restricted && (
                              <button type="button" className="btn btn-sm" onClick={() => setReleaseId(member.memberId)}>
                                해제
                              </button>
                            )}
                          </div>
                        ) : (
                          '-'
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={(nextPage) => updateQuery({ page: nextPage })} />
        </div>
      )}

      <MemberRestrictModal
        key={`restrict-${restrictTarget?.memberId ?? 'none'}`}
        member={restrictTarget}
        onClose={() => setRestrictId(null)}
        onDone={finish}
        onStale={refresh}
      />
      <MemberReleaseModal
        key={`release-${releaseTarget?.memberId ?? 'none'}`}
        member={releaseTarget}
        onClose={() => setReleaseId(null)}
        onDone={finish}
        onStale={refresh}
      />
    </div>
  );
}

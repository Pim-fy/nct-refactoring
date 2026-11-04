import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { fetchAdminDisputes } from '../../api/adminDisputeApi';
import { getErrorMessage } from '../../api/apiResponse';
import DisputeJudgeModal from '../../components/DisputeJudgeModal';
import Pagination from '../../components/Pagination';
import { useToast } from '../../hooks/useToast';
import {
  DISPUTE_JUDGMENT_LABELS,
  DISPUTE_REPORTER_LABELS,
  disputeStateBadge,
  disputeStateLabel,
  formatDateTime,
  formatWon,
  TRADE_STATUS_LABELS,
} from '../../utils/format';

const PAGE_SIZE = 20;
const STATUS_FILTERS = { OPEN: '처리 중(보류 유지 포함)', JUDGED: '판정 완료' };

// 관리자 분쟁 관리 (기능 명세서 3-10 분쟁 판정, 화면 설계서 관리자 분쟁 관리)
// - 목록: 상태로 거르고, 보류 유지(처리 중이면서 판정 결과가 보류)는 따로 표시한다.
// - 상세·판정: 거래 정보·배송 정보·분쟁 이력을 보고 반환·지급·거래 진행·보류 유지로 판정한다.
// 필터와 페이지는 주소창 쿼리에 보관한다.
export default function AdminDisputesPage() {
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const page = Math.max(Number.parseInt(searchParams.get('page') ?? '0', 10) || 0, 0);
  const rawStatus = searchParams.get('status') ?? '';
  const status = Object.keys(STATUS_FILTERS).includes(rawStatus) ? rawStatus : ''; // 주소창에 없는 상태가 있으면 전체로 본다.

  const [reload, setReload] = useState(0);
  const [result, setResult] = useState({ key: null, data: null, failed: false });
  const [judgeId, setJudgeId] = useState(null);

  const queryKey = `${page}|${status}|${reload}`;

  useEffect(() => {
    let cancelled = false;
    fetchAdminDisputes({ page, size: PAGE_SIZE, status: status || undefined })
      .then((data) => {
        if (cancelled) return;
        // 마지막 페이지의 마지막 항목을 처리해 페이지가 비면 마지막 페이지로 옮긴다.
        if (data.content.length === 0 && page > 0 && data.totalPages > 0) {
          setSearchParams((prev) => {
            const next = new URLSearchParams(prev);
            if (data.totalPages - 1 === 0) next.delete('page');
            else next.set('page', String(data.totalPages - 1));
            return next;
          });
          return;
        }
        setResult({ key: queryKey, data, failed: false });
        // 새 목록에 모달 대상이 없으면 대상을 비워, 나중에 모달이 불쑥 다시 열리지 않게 한다.
        setJudgeId((id) => (id !== null && data.content.some((item) => item.disputeId === id) ? id : null));
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
  }, [page, status, queryKey, setSearchParams, toast]);

  // 새로 받는 동안에도 이전 목록을 흐리게 보여 줘, 열려 있는 모달이 대상을 잃지 않게 한다.
  const loading = result.key !== queryKey;
  const data = result.data;
  const judgeTarget = data?.content.find((item) => item.disputeId === judgeId) ?? null;

  function updateQuery(changes) {
    const next = new URLSearchParams(searchParams);
    Object.entries(changes).forEach(([name, value]) => {
      if (value === '' || value === 0 || value === null) next.delete(name);
      else next.set(name, String(value));
    });
    setSearchParams(next);
  }

  // 판정이 끝난 분쟁의 모달만 닫는다. 제출 중에 다른 분쟁을 열었다면 그 모달은 그대로 둔다.
  function finish(doneId) {
    setJudgeId((id) => (id === doneId ? null : id));
    setReload((count) => count + 1);
  }

  return (
    <div className="rounded-box bg-base-100 p-6 shadow-sm">
      <h1 className="mb-1 text-xl font-bold">분쟁 관리</h1>
      <p className="mb-6 text-sm text-base-content/60">
        거래 당사자가 접수한 분쟁을 판정합니다. 반환·지급은 포인트가 움직여 되돌릴 수 없고, 보류 유지는 나중에 다시 판정할 수 있습니다.
      </p>

      <div className="mb-6 flex flex-wrap items-end gap-2">
        <label className="form-control">
          <span className="mb-1 block text-sm font-medium">상태</span>
          <select className="select select-bordered" value={status} onChange={(event) => updateQuery({ status: event.target.value, page: 0 })}>
            <option value="">전체</option>
            {Object.entries(STATUS_FILTERS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
      </div>

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
                  <th>상품</th>
                  <th className="w-28">거래가</th>
                  <th>접수자</th>
                  <th>사유</th>
                  <th className="w-24">접수 직전</th>
                  <th className="w-36">접수</th>
                  <th className="w-28">상태</th>
                  <th className="w-24">작업</th>
                </tr>
              </thead>
              <tbody>
                {data.content.length === 0 && (
                  <tr>
                    <td colSpan={8} className="py-8 text-center text-base-content/60">
                      조건에 맞는 분쟁이 없습니다.
                    </td>
                  </tr>
                )}
                {data.content.map((item) => (
                  <tr key={item.disputeId}>
                    <td className="max-w-xs break-words font-medium">{item.productName}</td>
                    <td className="text-sm">{formatWon(item.tradePrice)}</td>
                    <td className="text-sm">
                      <p>{item.reporterNickname}</p>
                      <p className="text-xs text-base-content/60">{DISPUTE_REPORTER_LABELS[item.reporterRole]}</p>
                    </td>
                    <td className="max-w-xs break-words text-sm">{item.reason}</td>
                    <td className="text-sm">{TRADE_STATUS_LABELS[item.previousTradeStatus] ?? item.previousTradeStatus}</td>
                    <td className="text-sm">{formatDateTime(item.createdAt)}</td>
                    <td>
                      <span className={`badge badge-sm ${disputeStateBadge(item.disputeStatus, item.judgment)}`}>
                        {disputeStateLabel(item.disputeStatus, item.judgment)}
                      </span>
                      {item.disputeStatus === 'JUDGED' && item.judgment && (
                        <p className="mt-1 text-xs text-base-content/60">{DISPUTE_JUDGMENT_LABELS[item.judgment]}</p>
                      )}
                    </td>
                    <td>
                      <button type="button" className="btn btn-sm btn-outline" disabled={loading} onClick={() => setJudgeId(item.disputeId)}>
                        {item.disputeStatus === 'OPEN' ? '판정' : '상세'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={(nextPage) => updateQuery({ page: nextPage })} />
        </div>
      )}

      <DisputeJudgeModal key={`judge-${judgeTarget?.disputeId ?? 'none'}`} item={judgeTarget} onClose={() => setJudgeId(null)} onDone={finish} />
    </div>
  );
}

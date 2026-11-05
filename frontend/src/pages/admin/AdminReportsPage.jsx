import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { fetchAdminReports } from '../../api/adminReportApi';
import { getErrorMessage } from '../../api/apiResponse';
import Pagination from '../../components/Pagination';
import ReportProcessModal from '../../components/ReportProcessModal';
import { useToast } from '../../hooks/useToast';
import {
  formatDateTime,
  REPORT_ACTION_LABELS,
  REPORT_REASON_LABELS,
  REPORT_STATUS_BADGES,
  REPORT_STATUS_LABELS,
  REPORT_TARGET_LABELS,
} from '../../utils/format';

const PAGE_SIZE = 20;
const DEFAULT_FILTER = 'RECEIVED'; // 주소창에 상태가 없으면 처리할 신고(접수)부터 보여 준다.
const FILTERS = [...Object.keys(REPORT_STATUS_LABELS), 'ALL'];

// 관리자 신고 관리 (기능 명세서 3-10 신고 처리, 화면 설계서 관리자 신고 관리)
// - 목록: 상태로 거르고 최근 신고가 먼저다. 처리한 신고는 결과와 사유를 보여 준다.
// - 처리: 접수 상태의 신고만 처리하며, 경매 중단·회원 이용 제한은 처리와 함께 수행된다. 반려한 신고는 다시 처리하지 않는다.
// 필터와 페이지는 주소창 쿼리에 보관한다.
export default function AdminReportsPage() {
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const page = Math.max(Number.parseInt(searchParams.get('page') ?? '0', 10) || 0, 0);
  const rawFilter = searchParams.get('status') ?? DEFAULT_FILTER;
  const filter = FILTERS.includes(rawFilter) ? rawFilter : DEFAULT_FILTER;

  const [reload, setReload] = useState(0);
  const [result, setResult] = useState({ key: null, data: null, failed: false });
  const [processId, setProcessId] = useState(null);

  const queryKey = `${page}|${filter}|${reload}`;

  useEffect(() => {
    let cancelled = false;
    fetchAdminReports({ page, size: PAGE_SIZE, status: filter === 'ALL' ? undefined : filter })
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
        setProcessId((id) => (id !== null && data.content.some((report) => report.reportId === id) ? id : null));
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
  }, [page, filter, queryKey, setSearchParams, toast]);

  // 새로 받는 동안에도 이전 목록을 흐리게 보여 줘, 열려 있는 모달이 대상을 잃지 않게 한다.
  const loading = result.key !== queryKey;
  const data = result.data;
  const processTarget = data?.content.find((report) => report.reportId === processId) ?? null;

  function changeFilter(value) {
    const next = new URLSearchParams(searchParams);
    next.delete('page');
    if (value === DEFAULT_FILTER) next.delete('status');
    else next.set('status', value);
    setSearchParams(next);
  }

  function changePage(nextPage) {
    const next = new URLSearchParams(searchParams);
    if (nextPage === 0) next.delete('page');
    else next.set('page', String(nextPage));
    setSearchParams(next);
  }

  function finish() {
    setProcessId(null);
    setReload((count) => count + 1);
  }

  return (
    <div className="rounded-box bg-base-100 p-6 shadow-sm">
      <h1 className="mb-1 text-xl font-bold">신고 관리</h1>
      <p className="mb-6 text-sm text-base-content/60">
        접수된 신고를 경매 중단, 회원 이용 제한 또는 반려로 처리합니다. 경매 중단과 이용 제한은 처리와 함께 적용되며, 처리한 신고는 다시 처리할 수 없습니다.
      </p>

      <div className="mb-6">
        <label className="form-control inline-block">
          <span className="mb-1 block text-sm font-medium">상태</span>
          <select className="select select-bordered" value={filter} onChange={(event) => changeFilter(event.target.value)}>
            {FILTERS.map((value) => (
              <option key={value} value={value}>
                {value === 'ALL' ? '전체' : REPORT_STATUS_LABELS[value]}
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
                  <th>대상</th>
                  <th>신고 내용</th>
                  <th className="w-32">신고일</th>
                  <th className="w-40">상태</th>
                  <th className="w-24">작업</th>
                </tr>
              </thead>
              <tbody>
                {data.content.length === 0 && (
                  <tr>
                    <td colSpan={5} className="py-8 text-center text-base-content/60">
                      조건에 맞는 신고가 없습니다.
                    </td>
                  </tr>
                )}
                {data.content.map((report) => (
                  <tr key={report.reportId}>
                    <td>
                      <p className="max-w-xs break-words font-medium">
                        <span className="badge badge-sm badge-outline mr-1">{REPORT_TARGET_LABELS[report.targetType] ?? report.targetType}</span>
                        {report.targetLabel}
                      </p>
                    </td>
                    <td className="text-sm">
                      <p>
                        {REPORT_REASON_LABELS[report.reportReason] ?? report.reportReason} · 신고자 {report.reporterNickname}
                      </p>
                      {report.detail && <p className="max-w-sm break-words text-xs text-base-content/60">{report.detail}</p>}
                      {report.actionReason && (
                        <p className="max-w-sm break-words text-xs text-base-content/60">처리 사유: {report.actionReason}</p>
                      )}
                    </td>
                    <td className="text-sm">{formatDateTime(report.createdAt)}</td>
                    <td>
                      <span className={`badge badge-sm ${REPORT_STATUS_BADGES[report.reportStatus] ?? 'badge-ghost'}`}>
                        {REPORT_STATUS_LABELS[report.reportStatus] ?? report.reportStatus}
                      </span>
                      {report.actionType && (
                        <p className="mt-1 text-xs text-base-content/60">
                          {REPORT_ACTION_LABELS[report.actionType] ?? report.actionType} · {formatDateTime(report.processedAt)}
                        </p>
                      )}
                    </td>
                    <td>
                      {report.reportStatus === 'RECEIVED' ? (
                        <button type="button" className="btn btn-sm btn-primary btn-outline" disabled={loading} onClick={() => setProcessId(report.reportId)}>
                          처리
                        </button>
                      ) : (
                        '-'
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={changePage} />
        </div>
      )}

      <ReportProcessModal key={`process-${processTarget?.reportId ?? 'none'}`} report={processTarget} onClose={() => setProcessId(null)} onDone={finish} />
    </div>
  );
}

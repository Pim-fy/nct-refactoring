import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { getErrorMessage } from '../api/apiResponse';
import { fetchMyReports } from '../api/reportApi';
import Pagination from '../components/Pagination';
import { useToast } from '../hooks/useToast';
import {
  formatDateTime,
  REPORT_ACTION_LABELS,
  REPORT_REASON_LABELS,
  REPORT_STATUS_BADGES,
  REPORT_STATUS_LABELS,
  REPORT_TARGET_LABELS,
} from '../utils/format';

const PAGE_SIZE = 10;

// 내 신고 내역 (기능 명세서 3-9, 화면 설계서 내 신고 내역). 본인이 접수한 신고와 처리 결과만 보여 준다.
// 처리 사유와 처리한 관리자는 보여 주지 않는다. 페이지는 주소창 쿼리에 보관한다.
export default function MyReportsPage() {
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const page = Math.max(Number.parseInt(searchParams.get('page') ?? '0', 10) || 0, 0);

  const [result, setResult] = useState({ page: null, data: null, failed: false });

  useEffect(() => {
    let cancelled = false;
    fetchMyReports({ page, size: PAGE_SIZE })
      .then((data) => {
        if (!cancelled) {
          setResult({ page, data, failed: false });
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setResult({ page, data: null, failed: true });
          toast.error(getErrorMessage(error));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [page, toast]);

  const loading = result.page !== page;
  const data = loading ? null : result.data;

  function goTo(nextPage) {
    const next = new URLSearchParams(searchParams);
    if (nextPage === 0) {
      next.delete('page');
    } else {
      next.set('page', String(nextPage));
    }
    setSearchParams(next);
  }

  return (
    <div className="mx-auto max-w-4xl rounded-box bg-base-100 p-6 shadow-sm">
      <h1 className="mb-4 text-xl font-bold">내 신고 내역</h1>

      {loading && (
        <div className="flex justify-center py-10">
          <span className="loading loading-spinner loading-lg text-primary" />
        </div>
      )}
      {!loading && result.failed && <p className="py-10 text-center text-base-content/60">목록을 불러오지 못했습니다.</p>}
      {data !== null && data.content.length === 0 && <p className="py-10 text-center text-base-content/60">접수한 신고가 없습니다.</p>}

      {data !== null && data.content.length > 0 && (
        <>
          <ul className="divide-y divide-base-300">
            {data.content.map((report) => (
              <li key={report.reportId} className="py-4">
                <div className="mb-1 flex flex-wrap items-center gap-2">
                  <span className={`badge badge-sm ${REPORT_STATUS_BADGES[report.reportStatus] ?? 'badge-ghost'}`}>
                    {REPORT_STATUS_LABELS[report.reportStatus] ?? report.reportStatus}
                  </span>
                  <span className="badge badge-sm badge-outline">{REPORT_TARGET_LABELS[report.targetType] ?? report.targetType}</span>
                  {report.actionType && <span className="badge badge-sm badge-info">{REPORT_ACTION_LABELS[report.actionType] ?? report.actionType}</span>}
                </div>
                <p className="font-medium">{report.targetLabel}</p>
                <p className="text-sm text-base-content/70">사유 {REPORT_REASON_LABELS[report.reportReason] ?? report.reportReason}</p>
                <p className="text-xs text-base-content/60">
                  접수 {formatDateTime(report.createdAt)}
                  {report.processedAt && ` · 처리 ${formatDateTime(report.processedAt)}`}
                </p>
              </li>
            ))}
          </ul>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={goTo} />
        </>
      )}
    </div>
  );
}

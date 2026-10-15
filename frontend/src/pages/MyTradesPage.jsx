import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { getErrorMessage } from '../api/apiResponse';
import { resolveImageUrl } from '../api/imageApi';
import { fetchMyTrades } from '../api/tradeApi';
import Pagination from '../components/Pagination';
import { useToast } from '../hooks/useToast';
import { formatDateTime, formatWon, TRADE_ROLE_LABELS, TRADE_STATUS_BADGES, TRADE_STATUS_LABELS } from '../utils/format';

const PAGE_SIZE = 10;

const ROLE_OPTIONS = [
  { value: '', label: '전체' },
  { value: 'BUYER', label: '구매' },
  { value: 'SELLER', label: '판매' },
];

// 내 거래 목록 (기능 명세서 3-8, 화면 설계서 내 거래 목록). 역할·상태·페이지는 주소창 쿼리에 보관한다.
// 상태에 해당하는 기한(준비면 발송 기한, 진행이면 구매 확정 기한)과 미이행 표시를 함께 보여 준다.
export default function MyTradesPage() {
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const role = ROLE_OPTIONS.some((option) => option.value === searchParams.get('role')) ? searchParams.get('role') : '';
  const status = Object.keys(TRADE_STATUS_LABELS).includes(searchParams.get('status')) ? searchParams.get('status') : '';
  const page = Math.max(Number.parseInt(searchParams.get('page') ?? '0', 10) || 0, 0);

  const [result, setResult] = useState({ key: null, data: null, failed: false });
  const requestKey = `${role}|${status}|${page}`;

  useEffect(() => {
    let cancelled = false;
    fetchMyTrades({ role, status, page, size: PAGE_SIZE })
      .then((data) => {
        if (!cancelled) {
          setResult({ key: requestKey, data, failed: false });
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setResult({ key: requestKey, data: null, failed: true });
          toast.error(getErrorMessage(error));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [role, status, page, requestKey, toast]);

  // 조건을 바꾸면 첫 페이지부터 본다.
  function update(changes) {
    const next = new URLSearchParams(searchParams);
    Object.entries({ page: '', ...changes }).forEach(([name, value]) => {
      if (value === '' || value === 0) {
        next.delete(name);
      } else {
        next.set(name, String(value));
      }
    });
    setSearchParams(next);
  }

  const loading = result.key !== requestKey;
  const data = loading ? null : result.data;

  return (
    <div className="mx-auto max-w-4xl rounded-box bg-base-100 p-6 shadow-sm">
      <h1 className="mb-4 text-xl font-bold">내 거래</h1>

      <div className="mb-4 flex flex-wrap gap-2">
        <div className="join">
          {ROLE_OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              className={`btn btn-sm join-item ${role === option.value ? 'btn-primary' : ''}`}
              onClick={() => update({ role: option.value })}
            >
              {option.label}
            </button>
          ))}
        </div>
        <select className="select select-sm select-bordered" value={status} aria-label="거래 상태" onChange={(event) => update({ status: event.target.value })}>
          <option value="">전체 상태</option>
          {Object.entries(TRADE_STATUS_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </div>

      {loading && (
        <div className="flex justify-center py-10">
          <span className="loading loading-spinner loading-lg text-primary" />
        </div>
      )}
      {!loading && result.failed && <p className="py-10 text-center text-base-content/60">목록을 불러오지 못했습니다.</p>}
      {data !== null && data.content.length === 0 && <p className="py-10 text-center text-base-content/60">거래가 없습니다.</p>}

      {data !== null && data.content.length > 0 && (
        <>
          <ul className="divide-y divide-base-300">
            {data.content.map((trade) => {
              const thumbnail = resolveImageUrl(trade.thumbnailUrl);
              return (
                <li key={trade.tradeId}>
                  <Link to={`/my/trades/${trade.tradeId}`} className="flex gap-4 py-4 hover:bg-base-200">
                    {thumbnail ? (
                      <img src={thumbnail} alt="" className="h-20 w-20 shrink-0 rounded object-cover" />
                    ) : (
                      <div className="h-20 w-20 shrink-0 rounded bg-base-200" />
                    )}
                    <div className="min-w-0 flex-1">
                      <div className="mb-1 flex flex-wrap items-center gap-2">
                        <span className={`badge badge-sm ${TRADE_STATUS_BADGES[trade.tradeStatus] ?? 'badge-ghost'}`}>
                          {TRADE_STATUS_LABELS[trade.tradeStatus] ?? trade.tradeStatus}
                        </span>
                        <span className="badge badge-sm badge-outline">{TRADE_ROLE_LABELS[trade.myRole]}</span>
                        {trade.isUnfulfilled && <span className="badge badge-sm badge-warning">미이행</span>}
                      </div>
                      <p className="truncate font-medium">{trade.productName}</p>
                      <p className="mt-1 text-sm text-base-content/70">거래 금액 {formatWon(trade.tradePrice)}</p>
                      {trade.dueAt && (
                        <p className="text-sm text-base-content/70">
                          {trade.tradeStatus === 'READY' ? '발송 기한' : '구매 확정 기한'} {formatDateTime(trade.dueAt)}
                        </p>
                      )}
                    </div>
                  </Link>
                </li>
              );
            })}
          </ul>
          <Pagination page={page} totalPages={data.totalPages} onChange={(next) => update({ page: next })} />
        </>
      )}
    </div>
  );
}

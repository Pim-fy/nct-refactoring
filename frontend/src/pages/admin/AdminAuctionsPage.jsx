import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { fetchAdminAuctions } from '../../api/adminAuctionApi';
import { getErrorMessage } from '../../api/apiResponse';
import AuctionStopModal from '../../components/AuctionStopModal';
import Pagination from '../../components/Pagination';
import { useToast } from '../../hooks/useToast';
import { AUCTION_STATUS_BADGES, AUCTION_STATUS_LABELS, formatDateTime, formatWon } from '../../utils/format';

const PAGE_SIZE = 20;
const STATUSES = Object.keys(AUCTION_STATUS_LABELS);

// 관리자 경매 관리 (기능 명세서 3-10 경매 강제 종료·숨김, 화면 설계서 관리자 경매 관리)
// - 목록: 중단된 경매를 포함해 상태·상품명으로 거르고, 중단된 경매는 사유를 보여 준다.
// - 중단: 등록·진행 중인 경매만 할 수 있다. 종료 시각이 지났지만 종료 처리 전인 경매도 가능하다.
// 필터와 페이지는 주소창 쿼리에 보관한다.
export default function AdminAuctionsPage() {
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const page = Math.max(Number.parseInt(searchParams.get('page') ?? '0', 10) || 0, 0);
  const rawStatus = searchParams.get('status') ?? '';
  const status = STATUSES.includes(rawStatus) ? rawStatus : ''; // 주소창에 없는 상태가 있으면 전체로 본다.
  const keyword = searchParams.get('keyword') ?? '';

  const [reload, setReload] = useState(0);
  const [result, setResult] = useState({ key: null, data: null, failed: false });
  const [stopId, setStopId] = useState(null);

  const queryKey = `${page}|${status}|${keyword}|${reload}`;

  useEffect(() => {
    let cancelled = false;
    fetchAdminAuctions({ page, size: PAGE_SIZE, status: status || undefined, keyword: keyword || undefined })
      .then((data) => {
        if (cancelled) return;
        setResult({ key: queryKey, data, failed: false });
        // 새 목록에 모달 대상이 없으면 대상을 비워, 나중에 모달이 불쑥 다시 열리지 않게 한다.
        setStopId((id) => (id !== null && data.content.some((auction) => auction.auctionId === id) ? id : null));
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

  // 새로 받는 동안에도 이전 목록을 흐리게 보여 줘, 열려 있는 모달이 대상을 잃지 않게 한다.
  const loading = result.key !== queryKey;
  const data = result.data;
  const stopTarget = data?.content.find((auction) => auction.auctionId === stopId) ?? null;

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

  function finish() {
    setStopId(null);
    setReload((count) => count + 1);
  }

  return (
    <div className="rounded-box bg-base-100 p-6 shadow-sm">
      <h1 className="mb-1 text-xl font-bold">경매 관리</h1>
      <p className="mb-6 text-sm text-base-content/60">
        등록·진행 중인 경매를 중단하면 목록에서 숨겨지고 입찰을 받지 않으며, 최고 입찰자의 예약 포인트가 해제됩니다. 중단은 되돌릴 수 없습니다.
      </p>

      <form onSubmit={handleSearch} className="mb-6 flex flex-wrap items-end gap-2">
        <label className="form-control">
          <span className="mb-1 block text-sm font-medium">상태</span>
          <select className="select select-bordered" value={status} onChange={(event) => updateQuery({ status: event.target.value, page: 0 })}>
            <option value="">전체</option>
            {Object.entries(AUCTION_STATUS_LABELS).map(([value, label]) => (
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
            placeholder="상품명"
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
                  <th>상품</th>
                  <th>판매자</th>
                  <th className="w-32">현재가</th>
                  <th className="w-20">입찰</th>
                  <th className="w-36">종료 시각</th>
                  <th className="w-24">상태</th>
                  <th className="w-24">작업</th>
                </tr>
              </thead>
              <tbody>
                {data.content.length === 0 && (
                  <tr>
                    <td colSpan={7} className="py-8 text-center text-base-content/60">
                      조건에 맞는 경매가 없습니다.
                    </td>
                  </tr>
                )}
                {data.content.map((auction) => {
                  const stoppable = auction.auctionStatus === 'REGISTERED' || auction.auctionStatus === 'IN_PROGRESS';
                  return (
                    <tr key={auction.auctionId}>
                      <td>
                        <p className="max-w-xs break-words font-medium">{auction.productName}</p>
                        {auction.stoppedReason && <p className="max-w-xs break-words text-xs text-base-content/60">중단 사유: {auction.stoppedReason}</p>}
                      </td>
                      <td className="text-sm">{auction.sellerNickname}</td>
                      <td className="text-sm">{formatWon(auction.currentPrice)}</td>
                      <td className="text-sm">{auction.bidCount}</td>
                      <td className="text-sm">{formatDateTime(auction.endAt)}</td>
                      <td>
                        <span className={`badge badge-sm ${AUCTION_STATUS_BADGES[auction.auctionStatus] ?? 'badge-ghost'}`}>
                          {AUCTION_STATUS_LABELS[auction.auctionStatus] ?? auction.auctionStatus}
                        </span>
                      </td>
                      <td>
                        {stoppable ? (
                          <button type="button" className="btn btn-sm btn-error btn-outline" disabled={loading} onClick={() => setStopId(auction.auctionId)}>
                            중단
                          </button>
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

      <AuctionStopModal key={`stop-${stopTarget?.auctionId ?? 'none'}`} auction={stopTarget} onClose={() => setStopId(null)} onDone={finish} />
    </div>
  );
}

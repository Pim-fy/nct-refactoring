import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getErrorMessage } from '../api/apiResponse';
import { fetchMyBids } from '../api/bidApi';
import { resolveImageUrl } from '../api/imageApi';
import Pagination from '../components/Pagination';
import { useToast } from '../hooks/useToast';
import { AUCTION_STATUS_BADGES, AUCTION_STATUS_LABELS, formatDateTime, formatRemaining, formatWon } from '../utils/format';

const PAGE_SIZE = 10;

// 내 입찰 목록 (기능 명세서 3-6, 화면 설계서 내 입찰 목록). 경매마다 한 줄이고 내가 마지막으로 입찰한 경매가 먼저다.
export default function MyBidsPage() {
  const toast = useToast();
  const [page, setPage] = useState(0);
  const [result, setResult] = useState({ page: null, data: null, failed: false });

  useEffect(() => {
    let cancelled = false;
    fetchMyBids({ page, size: PAGE_SIZE })
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

  return (
    <div className="mx-auto max-w-4xl rounded-box bg-base-100 p-6 shadow-sm">
      <h1 className="mb-4 text-xl font-bold">내 입찰</h1>

      {loading && (
        <div className="flex justify-center py-10">
          <span className="loading loading-spinner loading-lg text-primary" />
        </div>
      )}
      {!loading && result.failed && <p className="py-10 text-center text-base-content/60">목록을 불러오지 못했습니다.</p>}
      {data !== null && data.content.length === 0 && (
        <p className="py-10 text-center text-base-content/60">
          입찰한 경매가 없습니다.{' '}
          <Link to="/" className="link link-primary">
            경매 목록 보기
          </Link>
        </p>
      )}

      {data !== null && data.content.length > 0 && (
        <>
          <ul className="divide-y divide-base-300">
            {data.content.map((bid) => {
              const thumbnail = resolveImageUrl(bid.thumbnailUrl);
              const inProgress = bid.auctionStatus === 'IN_PROGRESS';
              return (
                <li key={bid.auctionId}>
                  <Link to={`/auctions/${bid.auctionId}`} className="flex gap-4 py-4 hover:bg-base-200">
                    {thumbnail ? (
                      <img src={thumbnail} alt="" className="h-20 w-20 shrink-0 rounded object-cover" />
                    ) : (
                      <div className="h-20 w-20 shrink-0 rounded bg-base-200" />
                    )}
                    <div className="min-w-0 flex-1">
                      <div className="mb-1 flex flex-wrap items-center gap-2">
                        <span className={`badge badge-sm ${AUCTION_STATUS_BADGES[bid.auctionStatus] ?? 'badge-ghost'}`}>
                          {AUCTION_STATUS_LABELS[bid.auctionStatus] ?? bid.auctionStatus}
                        </span>
                        {inProgress && (
                          <span className={`badge badge-sm ${bid.isTopBidder ? 'badge-success' : 'badge-warning'}`}>
                            {bid.isTopBidder ? '최고 입찰 중' : '밀려남'}
                          </span>
                        )}
                        {bid.isReserved && <span className="badge badge-sm badge-outline">포인트 예약 중</span>}
                      </div>
                      <p className="truncate font-medium">{bid.productName}</p>
                      <p className="mt-1 text-sm text-base-content/70">
                        내 입찰 {formatWon(bid.myBidPrice)} · 현재가 {formatWon(bid.currentPrice)}
                      </p>
                      <p className="text-sm text-base-content/70">
                        종료 {formatDateTime(bid.endAt)}
                        {inProgress && ` (${formatRemaining(bid.remainingSeconds)} 남음)`}
                      </p>
                    </div>
                  </Link>
                </li>
              );
            })}
          </ul>
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </div>
  );
}

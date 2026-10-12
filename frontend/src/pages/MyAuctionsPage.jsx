import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getErrorMessage } from '../api/apiResponse';
import { fetchMyAuctions } from '../api/auctionApi';
import { resolveImageUrl } from '../api/imageApi';
import { useToast } from '../hooks/useToast';
import { AUCTION_STATUS_BADGES, AUCTION_STATUS_LABELS, formatDateTime, formatRemaining, formatWon } from '../utils/format';

const PAGE_SIZE = 10;

// 내 경매 목록 (기능 명세서 3-4, 화면 설계서 내 경매 목록). 최신 등록순이다.
export default function MyAuctionsPage() {
  const toast = useToast();
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);   // null이면 불러오는 중이다.
  const [failed, setFailed] = useState(false);

  // 페이지가 바뀔 때마다 목록을 받는다. 요청 결과가 올 때만 상태를 바꾼다.
  useEffect(() => {
    let cancelled = false;
    fetchMyAuctions({ page, size: PAGE_SIZE })
      .then((result) => {
        if (!cancelled) {
          setData(result);
          setFailed(false);
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setFailed(true);
          toast.error(getErrorMessage(error));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [page, toast]);

  return (
    <div className="mx-auto max-w-4xl rounded-box bg-base-100 p-6 shadow-sm">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-bold">내 경매</h1>
        <Link to="/auctions/new" className="btn btn-primary btn-sm">
          경매 등록
        </Link>
      </div>

      {data === null && !failed && (
        <div className="flex justify-center py-10">
          <span className="loading loading-spinner loading-lg text-primary" />
        </div>
      )}
      {failed && <p className="py-10 text-center text-base-content/60">목록을 불러오지 못했습니다.</p>}

      {data !== null && data.content.length === 0 && (
        <p className="py-10 text-center text-base-content/60">
          등록한 경매가 없습니다. <Link to="/auctions/new" className="link link-primary">첫 경매를 등록해 보세요.</Link>
        </p>
      )}

      {data !== null && data.content.length > 0 && (
        <>
          <ul className="divide-y divide-base-300">
            {data.content.map((auction) => {
              const thumbnail = resolveImageUrl(auction.thumbnailUrl);
              return (
                <li key={auction.auctionId}>
                  <Link to={`/auctions/${auction.auctionId}`} className="flex gap-4 py-4 hover:bg-base-200">
                    {thumbnail ? (
                      <img src={thumbnail} alt="" className="h-20 w-20 shrink-0 rounded object-cover" />
                    ) : (
                      <div className="h-20 w-20 shrink-0 rounded bg-base-200" />
                    )}
                    <div className="min-w-0 flex-1">
                      <div className="mb-1 flex items-center gap-2">
                        <span className={`badge badge-sm ${AUCTION_STATUS_BADGES[auction.auctionStatus] ?? 'badge-ghost'}`}>
                          {AUCTION_STATUS_LABELS[auction.auctionStatus] ?? auction.auctionStatus}
                        </span>
                        <span className="text-xs text-base-content/60">{auction.categoryName}</span>
                      </div>
                      <p className="truncate font-medium">{auction.productName}</p>
                      <p className="mt-1 text-sm text-base-content/70">
                        시작가 {formatWon(auction.startPrice)} · 현재가 {formatWon(auction.currentPrice)} · 입찰 {auction.bidCount}건
                      </p>
                      <p className="text-sm text-base-content/70">
                        종료 {formatDateTime(auction.endAt)}
                        {auction.auctionStatus === 'IN_PROGRESS' && ` (${formatRemaining(auction.remainingSeconds)} 남음)`}
                      </p>
                    </div>
                  </Link>
                </li>
              );
            })}
          </ul>

          {data.totalPages > 1 && (
            <div className="mt-4 flex items-center justify-center gap-3">
              <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
                이전
              </button>
              <span className="text-sm">
                {data.page + 1} / {data.totalPages}
              </span>
              <button type="button" className="btn btn-sm" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>
                다음
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}

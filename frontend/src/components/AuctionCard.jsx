import { Link } from 'react-router-dom';
import { resolveImageUrl } from '../api/imageApi';
import { AUCTION_STATUS_BADGES, AUCTION_STATUS_LABELS, formatRemaining, formatWon } from '../utils/format';

// 경매 목록의 카드 한 장. (화면 설계서 3-2) 대표 이미지, 상품명, 현재가, 남은 시간, 입찰 수를 보여 준다.
// 입찰이 없으면 현재가 자리에 시작가를 보여 준다.
export default function AuctionCard({ auction }) {
  const thumbnail = resolveImageUrl(auction.thumbnailUrl);
  const inProgress = auction.auctionStatus === 'IN_PROGRESS';
  const hasBid = auction.currentPrice !== null && auction.currentPrice !== undefined;

  return (
    <Link to={`/auctions/${auction.auctionId}`} className="card bg-base-100 shadow-sm transition hover:shadow-md">
      <figure className="aspect-square bg-base-200">
        {thumbnail ? (
          <img src={thumbnail} alt="" className="h-full w-full object-cover" />
        ) : (
          <span className="text-sm text-base-content/40">이미지 없음</span>
        )}
      </figure>
      <div className="card-body gap-1 p-3">
        <div className="flex items-center gap-1">
          {!inProgress && (
            <span className={`badge badge-sm ${AUCTION_STATUS_BADGES[auction.auctionStatus] ?? 'badge-ghost'}`}>
              {AUCTION_STATUS_LABELS[auction.auctionStatus] ?? auction.auctionStatus}
            </span>
          )}
          <span className="truncate text-xs text-base-content/60">{auction.categoryName}</span>
        </div>
        <h2 className="truncate font-medium">{auction.productName}</h2>
        <p className="font-bold text-primary">
          <span className="mr-1 text-xs font-normal text-base-content/60">{hasBid ? '현재가' : '시작가'}</span>
          {formatWon(hasBid ? auction.currentPrice : auction.startPrice)}
        </p>
        <p className="text-xs text-base-content/70">
          {inProgress ? `${formatRemaining(auction.remainingSeconds)} 남음` : '종료된 경매'} · 입찰 {auction.bidCount}건
        </p>
      </div>
    </Link>
  );
}

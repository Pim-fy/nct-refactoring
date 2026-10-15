import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getErrorCode, getErrorMessage } from '../api/apiResponse';
import { fetchAuction } from '../api/auctionApi';
import { resolveImageUrl } from '../api/imageApi';
import BidHistory from '../components/BidHistory';
import BidPanel from '../components/BidPanel';
import { useAuth } from '../hooks/useAuth';
import { useToast } from '../hooks/useToast';
import { AUCTION_STATUS_BADGES, AUCTION_STATUS_LABELS, formatDateTime, formatRemaining, formatWon } from '../utils/format';

// 경매 상세 (화면 설계서 3-3, 기능 명세서 3-4, 3-6). 입찰할 수 있는 회원에게는 입찰 영역을, 모두에게 입찰 내역을 보여 준다.
export default function AuctionDetailPage() {
  const { id } = useParams();
  const toast = useToast();
  // 요청한 경매(id)와 결과를 함께 두어, 다른 경매로 바뀌면 이전 결과를 "불러오는 중"으로 본다.
  const [result, setResult] = useState({ id: null, auction: null, status: 'loading' });
  const validId = /^\d+$/.test(id);   // 숫자가 아닌 주소는 서버에 묻지 않고 없는 경매로 본다.
  const [version, setVersion] = useState(0);   // 입찰해서 경매 정보가 바뀌었을 때 다시 받기 위한 값

  useEffect(() => {
    if (!validId) {
      return undefined;
    }
    let cancelled = false;
    fetchAuction(id)
      .then((auction) => {
        if (!cancelled) {
          setResult({ id, auction, status: 'ok' });
        }
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        if (getErrorCode(error) === 'AUCTION_NOT_FOUND') {
          setResult({ id, auction: null, status: 'notFound' });
        } else {
          // 입찰 뒤 다시 받다가 실패하면 이미 보이던 경매는 그대로 두고 알림만 보여 준다.
          setResult((previous) => (previous.id === id && previous.status === 'ok' ? previous : { id, auction: null, status: 'failed' }));
          toast.error(getErrorMessage(error));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [id, validId, version, toast]);

  if (!validId) {
    return <NotFound message="경매를 찾을 수 없습니다." />;
  }
  if (result.id !== id) {
    return (
      <div className="flex justify-center py-16">
        <span className="loading loading-spinner loading-lg text-primary" />
      </div>
    );
  }
  if (result.status !== 'ok') {
    return <NotFound message={result.status === 'notFound' ? '경매를 찾을 수 없습니다.' : '경매 정보를 불러오지 못했습니다.'} />;
  }
  return <AuctionDetailView auction={result.auction} version={version} onChanged={() => setVersion((value) => value + 1)} />;
}

// 로그인했는데 입찰할 수 없는 이유를 알려 준다. 판매자 본인은 따로 안내하므로 여기 오지 않는다.
function cannotBidReason(user, bidding) {
  if (user.memberRole === 'ADMIN') {
    return '관리자 계정은 입찰할 수 없습니다.';
  }
  if (user.memberStatus === 'RESTRICTED_TRADE') {
    return '거래 제한 상태에서는 입찰할 수 없습니다.';
  }
  if (bidding.remainingSeconds <= 0) {
    return '마감 시각이 지나 입찰할 수 없습니다. 종료 처리를 기다리는 중입니다.';
  }
  return '현재 계정으로는 입찰할 수 없습니다.';
}

// 낙찰 결과(화면 설계서 3-3). 낙찰 금액과 낙찰자(일부 가림), 확정 시각을 보여 주고,
// 낙찰자와 판매자에게는 거래 단계 안내를 덧붙인다. 거래 화면은 이후 단계에서 연결한다.
function SoldNotice({ result, isSeller }) {
  if (!result) {
    return <p className="alert alert-success mt-3 text-sm">낙찰된 경매입니다.</p>;
  }
  return (
    <div className="mt-3 rounded-box border border-success/40 bg-success/10 p-4 text-sm">
      <p className="font-bold text-success">낙찰되었습니다</p>
      <dl className="mt-2 grid grid-cols-[6rem_1fr] gap-y-1">
        <dt className="text-base-content/60">낙찰 금액</dt>
        <dd className="font-medium">{formatWon(result.winningPrice)}</dd>
        <dt className="text-base-content/60">낙찰자</dt>
        <dd>
          {result.maskedWinnerNickname}
          {result.isWinner && ' (나)'}
        </dd>
        <dt className="text-base-content/60">확정 시각</dt>
        <dd>{formatDateTime(result.confirmedAt)}</dd>
      </dl>
      {result.isWinner && (
        <p className="mt-3">
          낙찰 금액은 포인트로 예약되어 있습니다. 내 거래에서 배송지를 입력하면 판매자가 택배로 발송합니다.
        </p>
      )}
      {isSeller && (
        <p className="mt-3">낙찰자가 배송지를 입력하면 낙찰 후 5일 안에 택배로 발송해 주세요. 배송비는 판매자가 부담합니다.</p>
      )}
      {(result.isWinner || isSeller) && (
        <Link to="/my/trades" className="btn btn-primary btn-sm mt-3">
          내 거래 보기
        </Link>
      )}
    </div>
  );
}

function NotFound({ message }) {
  return (
    <div className="mx-auto max-w-md py-16 text-center">
      <p className="text-base-content/70">{message}</p>
      <Link to="/" className="btn btn-primary btn-sm mt-4">
        경매 목록으로
      </Link>
    </div>
  );
}

function AuctionDetailView({ auction, version, onChanged }) {
  const { user } = useAuth();
  const { product, terms, bidding, seller } = auction;
  const [selected, setSelected] = useState(0);
  const images = product.images;
  const current = images[selected];
  const inProgress = auction.auctionStatus === 'IN_PROGRESS';
  const hasBid = bidding.currentPrice !== null && bidding.currentPrice !== undefined;

  return (
    <div className="mx-auto max-w-5xl rounded-box bg-base-100 p-6 shadow-sm">
      <div className="grid gap-6 md:grid-cols-2">
        <div>
          <div className="relative flex aspect-square items-center justify-center overflow-hidden rounded-box bg-base-200">
            {current ? (
              <img src={resolveImageUrl(current.url)} alt={`${product.name} 이미지 ${selected + 1}`} className="h-full w-full object-contain" />
            ) : (
              <span className="text-base-content/40">이미지 없음</span>
            )}
            {images.length > 1 && (
              <>
                <button
                  type="button"
                  className="btn btn-circle btn-sm absolute left-2 top-1/2 -translate-y-1/2"
                  aria-label="이전 이미지"
                  onClick={() => setSelected((selected - 1 + images.length) % images.length)}
                >
                  ‹
                </button>
                <button
                  type="button"
                  className="btn btn-circle btn-sm absolute right-2 top-1/2 -translate-y-1/2"
                  aria-label="다음 이미지"
                  onClick={() => setSelected((selected + 1) % images.length)}
                >
                  ›
                </button>
                <span className="badge absolute bottom-2 right-2">
                  {selected + 1} / {images.length}
                </span>
              </>
            )}
          </div>
          {images.length > 1 && (
            <ul className="mt-2 flex gap-2 overflow-x-auto">
              {images.map((image, index) => (
                <li key={image.imageId} className="shrink-0">
                  <button
                    type="button"
                    className={`block h-14 w-14 overflow-hidden rounded border-2 ${index === selected ? 'border-primary' : 'border-transparent'}`}
                    aria-label={`이미지 ${index + 1} 보기`}
                    onClick={() => setSelected(index)}
                  >
                    <img src={resolveImageUrl(image.url)} alt="" className="h-full w-full object-cover" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div>
          <div className="mb-1 flex items-center gap-2">
            <span className={`badge ${AUCTION_STATUS_BADGES[auction.auctionStatus] ?? 'badge-ghost'}`}>
              {AUCTION_STATUS_LABELS[auction.auctionStatus] ?? auction.auctionStatus}
            </span>
            <span className="text-sm text-base-content/60">{product.categoryName}</span>
          </div>
          <h1 className="text-2xl font-bold">{product.name}</h1>
          <p className="mt-1 text-sm text-base-content/60">
            판매자 {seller.maskedNickname}
            {seller.isMine && ' (내가 등록한 경매)'}
          </p>

          <div className="mt-4 rounded-box bg-base-200 p-4">
            <p className="text-sm text-base-content/60">{hasBid ? '현재가' : '입찰 없음 · 시작가'}</p>
            <p className="text-3xl font-bold text-primary">{formatWon(hasBid ? bidding.currentPrice : terms.startPrice)}</p>
            <p className="mt-1 text-sm">입찰 {bidding.bidCount}건</p>
            {inProgress && (
              <>
                <p className="mt-2 text-sm">
                  남은 시간 <span className="font-medium">{formatRemaining(bidding.remainingSeconds)}</span>
                </p>
                <p className="text-sm">
                  다음 입찰 최소 금액 <span className="font-medium">{formatWon(bidding.minimumBidPrice)}</span>
                </p>
              </>
            )}
          </div>

          {bidding.canBid && (
            <BidPanel
              key={`${bidding.minimumBidPrice}|${bidding.isTopBidder}`}
              auctionId={auction.auctionId}
              bidding={bidding}
              bidUnit={terms.bidUnit}
              onChanged={onChanged}
            />
          )}
          {inProgress && !user && (
            <p className="mt-4 rounded-box border border-base-300 p-4 text-sm">
              입찰하려면 로그인해야 합니다.{' '}
              <Link to="/login" className="link link-primary">
                로그인
              </Link>
            </p>
          )}
          {inProgress && user && !bidding.canBid && !seller.isMine && (
            <p className="mt-4 rounded-box border border-base-300 p-4 text-sm text-base-content/70">
              {cannotBidReason(user, bidding)}
            </p>
          )}

          {auction.auctionStatus === 'SOLD' && <SoldNotice result={auction.result} isSeller={seller.isMine} />}
          {auction.auctionStatus === 'FAILED' && <p className="alert mt-3 text-sm">입찰 없이 종료된 경매입니다.</p>}
          {auction.auctionStatus === 'STOPPED' && (
            <p className="alert alert-error mt-3 text-sm">
              운영자가 중단한 경매입니다.{auction.stoppedReason && ` 사유: ${auction.stoppedReason}`}
            </p>
          )}

          <dl className="mt-4 grid grid-cols-[6rem_1fr] gap-y-1 text-sm">
            <dt className="text-base-content/60">시작가</dt>
            <dd>{formatWon(terms.startPrice)}</dd>
            <dt className="text-base-content/60">입찰 단위</dt>
            <dd>{formatWon(terms.bidUnit)}</dd>
            <dt className="text-base-content/60">종료 시각</dt>
            <dd>{formatDateTime(terms.endAt)}</dd>
            <dt className="text-base-content/60">배송</dt>
            <dd>택배, 배송비 판매자 부담</dd>
          </dl>
        </div>
      </div>

      <section className="mt-8">
        <h2 className="mb-2 font-bold">상품 설명</h2>
        <p className="whitespace-pre-wrap text-sm">{product.description}</p>
      </section>
      <section className="mt-6">
        <h2 className="mb-2 font-bold">상태 설명</h2>
        <p className="whitespace-pre-wrap text-sm">{product.conditionNote}</p>
      </section>

      <BidHistory auctionId={auction.auctionId} version={version} />
    </div>
  );
}

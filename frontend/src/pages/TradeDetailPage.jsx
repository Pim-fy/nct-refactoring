import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getErrorCode, getErrorMessage } from '../api/apiResponse';
import { resolveImageUrl } from '../api/imageApi';
import { confirmPurchase, fetchTrade } from '../api/tradeApi';
import DeliveryForm from '../components/DeliveryForm';
import Modal from '../components/Modal';
import ReportModal from '../components/ReportModal';
import ShipmentForm from '../components/ShipmentForm';
import TradeCancelPanel from '../components/TradeCancelPanel';
import TradeDisputePanel from '../components/TradeDisputePanel';
import { useToast } from '../hooks/useToast';
import { formatDateTime, formatPoint, formatWon, TRADE_ROLE_LABELS, TRADE_STATUS_BADGES, TRADE_STATUS_LABELS } from '../utils/format';

// 거래 상세 (화면 설계서 3-5, 기능 명세서 3-8). 당사자만 볼 수 있다.
// 버튼은 서버가 내려주는 availableActions로 만든다. 배송지 입력은 낙찰자, 발송 등록은 판매자, 구매 확정은 낙찰자의 행동이다.
export default function TradeDetailPage() {
  const { id } = useParams();
  const toast = useToast();
  const validId = /^\d+$/.test(id);   // 숫자가 아닌 주소는 서버에 묻지 않고 없는 거래로 본다.
  // 요청한 거래(id)와 결과를 함께 두어, 다른 거래로 바뀌면 이전 결과를 "불러오는 중"으로 본다.
  const [result, setResult] = useState({ id: null, trade: null, status: 'loading' });
  const [version, setVersion] = useState(0);   // 상태를 바꾼 뒤 다시 받기 위한 값

  useEffect(() => {
    if (!validId) {
      return undefined;
    }
    let cancelled = false;
    fetchTrade(id)
      .then((trade) => {
        if (!cancelled) {
          setResult({ id, trade, status: 'ok' });
        }
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        const code = getErrorCode(error);
        if (code === 'TRADE_NOT_FOUND' || code === 'TRADE_ACCESS_DENIED') {
          setResult({ id, trade: null, status: code === 'TRADE_NOT_FOUND' ? 'notFound' : 'denied' });
        } else {
          // 상태를 바꾼 뒤 다시 받다가 실패하면 이미 보이던 거래는 그대로 두고 알림만 보여 준다.
          setResult((previous) => (previous.id === id && previous.status === 'ok' ? previous : { id, trade: null, status: 'failed' }));
          toast.error(getErrorMessage(error));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [id, validId, version, toast]);

  if (!validId) {
    return <Message text="거래를 찾을 수 없습니다." />;
  }
  if (result.id !== id) {
    return (
      <div className="flex justify-center py-16">
        <span className="loading loading-spinner loading-lg text-primary" />
      </div>
    );
  }
  if (result.status === 'notFound') {
    return <Message text="거래를 찾을 수 없습니다." />;
  }
  if (result.status === 'denied') {
    return <Message text="이 거래를 볼 수 있는 권한이 없습니다." />;
  }
  if (result.status !== 'ok') {
    return <Message text="거래 정보를 불러오지 못했습니다." />;
  }
  return <TradeDetailView trade={result.trade} onChanged={() => setVersion((value) => value + 1)} />;
}

function Message({ text }) {
  return (
    <div className="mx-auto max-w-md py-16 text-center">
      <p className="text-base-content/70">{text}</p>
      <Link to="/my/trades" className="btn btn-primary btn-sm mt-4">
        내 거래로
      </Link>
    </div>
  );
}

// 현재 상태에 해당하는 기한 안내만 보여 준다. 발송 전에는 구매 확정 기한을 보여 주지 않는다.
function ScheduleNotice({ trade }) {
  const { schedule, tradeStatus } = trade;
  if (tradeStatus === 'READY') {
    return (
      <>
        <p>발송 기한 {formatDateTime(schedule.shipDueAt)}까지</p>
        {schedule.isUnfulfilled && (
          <p className="mt-1 font-medium text-warning">
            발송 기한이 지났습니다.{trade.availableActions.includes('OPEN_DISPUTE') && ' 분쟁을 접수할 수 있습니다.'}
          </p>
        )}
      </>
    );
  }
  if (tradeStatus === 'IN_PROGRESS') {
    return <p>구매 확정 기한 {formatDateTime(schedule.confirmDueAt)}까지 (기한이 지나면 자동으로 구매 확정됩니다)</p>;
  }
  if (tradeStatus === 'CONFIRMED') {
    return <p>구매가 확정되었습니다. 포인트 지급 처리가 아직 끝나지 않았으며 잠시 후 자동으로 이어서 처리됩니다.</p>;
  }
  if (tradeStatus === 'DISPUTED') {
    return <p>분쟁이 접수되어 관리자 판정을 기다리고 있습니다. 판정이 나올 때까지 거래 상태를 바꿀 수 없고 자동 구매 확정도 진행되지 않습니다.</p>;
  }
  if (tradeStatus === 'CANCELED') {
    return <p>거래가 취소되었습니다. ({formatDateTime(schedule.completedAt)}) 낙찰자의 예약 포인트가 반환되었습니다.</p>;
  }
  if (tradeStatus === 'COMPLETED') {
    return (
      <p>
        거래가 완료되었습니다. ({formatDateTime(schedule.completedAt)}
        {schedule.isAutoConfirmed && ', 자동 구매 확정'})
      </p>
    );
  }
  return null;
}

function TradeDetailView({ trade, onChanged }) {
  const toast = useToast();
  const { delivery, availableActions: actions } = trade;
  const [confirming, setConfirming] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [reporting, setReporting] = useState(false);
  const [confirmed, setConfirmed] = useState(false);   // 확정을 보낸 뒤 상세를 다시 받기 전까지 버튼을 다시 누르지 못하게 한다.
  const thumbnail = resolveImageUrl(trade.auction.thumbnailUrl);
  const isSeller = trade.myRole === 'SELLER';

  async function handleConfirm() {
    setSubmitting(true);
    try {
      const result = await confirmPurchase(trade.tradeId);
      setConfirmed(true);
      setConfirming(false);
      if (result.tradeStatus === 'COMPLETED') {
        toast.success('구매를 확정했고 포인트가 지급되었습니다.');
      } else {
        toast.error('구매는 확정되었지만 포인트 지급이 아직 끝나지 않았습니다. 잠시 후 자동으로 이어서 처리됩니다.');
      }
      onChanged();
    } catch (error) {
      setConfirming(false);
      toast.error(getErrorMessage(error));
      onChanged();
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-3xl rounded-box bg-base-100 p-6 shadow-sm">
      <div className="flex gap-4">
        {thumbnail ? (
          <img src={thumbnail} alt="" className="h-24 w-24 shrink-0 rounded object-cover" />
        ) : (
          <div className="h-24 w-24 shrink-0 rounded bg-base-200" />
        )}
        <div className="min-w-0 flex-1">
          <div className="mb-1 flex flex-wrap items-center gap-2">
            <span className={`badge ${TRADE_STATUS_BADGES[trade.tradeStatus] ?? 'badge-ghost'}`}>
              {TRADE_STATUS_LABELS[trade.tradeStatus] ?? trade.tradeStatus}
            </span>
            <span className="badge badge-outline">{TRADE_ROLE_LABELS[trade.myRole]}</span>
            {trade.schedule.isUnfulfilled && <span className="badge badge-warning">미이행</span>}
          </div>
          <Link to={`/auctions/${trade.auction.auctionId}`} className="link-hover truncate text-lg font-bold">
            {trade.auction.productName}
          </Link>
          <p className="text-sm text-base-content/70">
            {isSeller ? '낙찰자' : '판매자'} {trade.counterparty.maskedNickname}
          </p>
          <p className="text-lg font-bold text-primary">낙찰 금액 {formatWon(trade.tradePrice)}</p>
          <button type="button" className="btn btn-xs btn-ghost mt-1 text-error" onClick={() => setReporting(true)}>
            거래 신고하기
          </button>
        </div>
      </div>

      <div className="mt-4 rounded-box bg-base-200 p-4 text-sm">
        <ScheduleNotice trade={trade} />
      </div>

      {delivery && (
        <section className="mt-4">
          <h2 className="mb-2 font-bold">배송 정보</h2>
          <dl className="grid grid-cols-[6rem_1fr] gap-y-1 text-sm">
            <dt className="text-base-content/60">수령인</dt>
            <dd>{delivery.receiverName}</dd>
            <dt className="text-base-content/60">연락처</dt>
            <dd>{delivery.receiverPhone}</dd>
            <dt className="text-base-content/60">주소</dt>
            <dd>
              ({delivery.zipCode}) {delivery.address} {delivery.addressDetail}
            </dd>
            {delivery.deliveryMessage && (
              <>
                <dt className="text-base-content/60">요청 사항</dt>
                <dd>{delivery.deliveryMessage}</dd>
              </>
            )}
          </dl>
          {delivery.trackingNumber && (
            <>
              <h2 className="mb-2 mt-4 font-bold">운송장</h2>
              <dl className="grid grid-cols-[6rem_1fr] gap-y-1 text-sm">
                <dt className="text-base-content/60">택배사</dt>
                <dd>{delivery.courierName}</dd>
                <dt className="text-base-content/60">운송장 번호</dt>
                <dd>{delivery.trackingNumber}</dd>
                <dt className="text-base-content/60">발송 시각</dt>
                <dd>{formatDateTime(delivery.shippedAt)}</dd>
              </dl>
            </>
          )}
        </section>
      )}

      {/* 당사자별 행동 영역: 서버가 내려준 availableActions에 있는 것만 보여 준다. */}
      {actions.includes('INPUT_DELIVERY') && (
        <DeliveryForm key={delivery ? 'edit' : 'new'} tradeId={trade.tradeId} delivery={delivery} onSaved={onChanged} />
      )}
      {actions.includes('REGISTER_SHIPMENT') && <ShipmentForm tradeId={trade.tradeId} delivery={delivery} onRegistered={onChanged} />}
      {isSeller && trade.tradeStatus === 'READY' && !delivery && (
        <p className="mt-4 rounded-box border border-base-300 p-4 text-sm">낙찰자가 배송지를 입력하면 발송을 등록할 수 있습니다.</p>
      )}
      {actions.includes('CONFIRM_PURCHASE') && (
        <div className="mt-4 rounded-box border border-base-300 p-4">
          <h2 className="mb-1 font-bold">구매 확정</h2>
          <p className="mb-3 text-sm text-base-content/70">물건을 받았다면 구매를 확정해 주세요. 확정하면 낙찰 금액이 판매자에게 지급됩니다.</p>
          <button type="button" className="btn btn-primary w-full" onClick={() => setConfirming(true)} disabled={submitting || confirmed}>
            구매 확정
          </button>
        </div>
      )}

      <TradeCancelPanel trade={trade} onChanged={onChanged} />
      <TradeDisputePanel trade={trade} onChanged={onChanged} />

      <ReportModal open={reporting} targetType="TRADE" targetId={trade.tradeId} targetLabel={trade.auction.productName} onClose={() => setReporting(false)} />

      <Modal open={confirming} title="구매를 확정할까요?" onClose={() => setConfirming(false)}>
        <p className="mb-4 rounded bg-warning/20 p-3 text-sm">
          구매를 확정하면 예약된 {formatPoint(trade.tradePrice)}가 판매자에게 지급되고 되돌릴 수 없습니다. 물건을 확인했는지 다시 한 번 확인해 주세요.
        </p>
        <button type="button" className="btn btn-primary" disabled={submitting} onClick={handleConfirm}>
          {submitting ? '처리 중...' : '구매 확정'}
        </button>
      </Modal>
    </div>
  );
}

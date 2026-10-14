import { useEffect, useState } from 'react';
import { getErrorMessage } from '../api/apiResponse';
import { fetchBids } from '../api/bidApi';
import { useToast } from '../hooks/useToast';
import { formatDateTime, formatWon } from '../utils/format';
import Pagination from './Pagination';

const PAGE_SIZE = 10;

// 입찰 내역 (화면 설계서 3-3). 금액이 높은 순이고 입찰자는 일부를 가려서 보여 준다.
// version이 바뀌면(내가 입찰했거나 경매를 다시 받았으면) 다시 불러온다.
export default function BidHistory({ auctionId, version }) {
  const toast = useToast();
  const [page, setPage] = useState(0);
  const [result, setResult] = useState({ key: null, data: null, failed: false });
  const requestKey = `${auctionId}|${page}|${version}`;

  useEffect(() => {
    let cancelled = false;
    fetchBids(auctionId, { page, size: PAGE_SIZE })
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
  }, [auctionId, page, requestKey, toast]);

  const loading = result.key !== requestKey && result.data === null;
  const data = result.data;

  return (
    <section className="mt-8">
      <h2 className="mb-2 font-bold">입찰 내역{data !== null && ` (${data.totalElements}건)`}</h2>
      {loading && <span className="loading loading-spinner text-primary" />}
      {result.failed && <p className="text-sm text-base-content/60">입찰 내역을 불러오지 못했습니다.</p>}
      {data !== null && data.content.length === 0 && <p className="text-sm text-base-content/60">아직 입찰이 없습니다.</p>}
      {data !== null && data.content.length > 0 && (
        <>
          <div className="overflow-x-auto">
            <table className="table table-sm">
              <thead>
                <tr>
                  <th>금액</th>
                  <th>시각</th>
                  <th>입찰자</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((bid) => (
                  <tr key={bid.bidId} className={bid.isMine ? 'bg-primary/10' : ''}>
                    <td className="font-medium">{formatWon(bid.bidPrice)}</td>
                    <td>{formatDateTime(bid.bidAt)}</td>
                    <td>
                      {bid.maskedBidderNickname}
                      {bid.isMine && ' (나)'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </section>
  );
}

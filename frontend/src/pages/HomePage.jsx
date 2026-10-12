import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { getErrorMessage } from '../api/apiResponse';
import { fetchAuctions } from '../api/auctionApi';
import { fetchCategories } from '../api/categoryApi';
import AuctionCard from '../components/AuctionCard';
import Pagination from '../components/Pagination';
import { useToast } from '../hooks/useToast';

const PAGE_SIZE = 12;

const SORT_OPTIONS = [
  { value: 'endAt', label: '마감 임박순' },
  { value: 'createdAt', label: '최신 등록순' },
  { value: 'price', label: '현재가 낮은순' },
  { value: 'bidCount', label: '입찰 많은순' },
];

// 홈·경매 목록 (화면 설계서 3-2, 기능 명세서 3-4)
// 검색어·카테고리·정렬·페이지는 주소창 쿼리에 보관한다. 새로고침과 뒤로 가기에서도 유지되고 링크로 공유할 수 있다.
export default function HomePage() {
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const rawCategory = searchParams.get('category') ?? '';
  const category = /^\d+$/.test(rawCategory) ? rawCategory : '';   // 숫자가 아닌 값은 서버에 보내지 않는다.
  const keyword = searchParams.get('keyword') ?? '';
  const sort = SORT_OPTIONS.some((option) => option.value === searchParams.get('sort')) ? searchParams.get('sort') : 'endAt';
  const page = Math.max(Number.parseInt(searchParams.get('page') ?? '0', 10) || 0, 0);

  const [categories, setCategories] = useState([]);
  // 요청한 조건(key)과 결과를 함께 두어, 조건이 바뀌면 이전 결과를 "불러오는 중"으로 본다.
  const [result, setResult] = useState({ key: null, data: null, failed: false });
  const requestKey = `${category}|${keyword}|${sort}|${page}`;

  useEffect(() => {
    let cancelled = false;
    fetchCategories()
      .then((response) => {
        if (!cancelled) {
          setCategories(response.categories);
        }
      })
      .catch(() => {});   // 카테고리를 받지 못해도 목록은 볼 수 있다.
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    fetchAuctions({ category, keyword, sort, page, size: PAGE_SIZE })
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
  }, [category, keyword, sort, page, requestKey, toast]);

  // 조건을 바꾸면 첫 페이지부터 본다. page를 따로 넘기면 그 값을 쓴다.
  function update(changes) {
    const next = new URLSearchParams(searchParams);
    const merged = { page: '', ...changes };
    Object.entries(merged).forEach(([name, value]) => {
      if (value === '' || value === null || value === undefined || value === 0 || (name === 'sort' && value === 'endAt')) {
        next.delete(name);
      } else {
        next.set(name, String(value));
      }
    });
    setSearchParams(next);
  }

  function handleSearch(event) {
    event.preventDefault();
    update({ keyword: new FormData(event.currentTarget).get('keyword').trim() });
  }

  const loading = result.key !== requestKey;
  const data = loading ? null : result.data;

  return (
    <div className="mx-auto max-w-6xl">
      <div className="mb-4 flex flex-wrap items-center gap-2">
        {/* key가 바뀌면 입력칸이 새로 만들어져, 뒤로 가기로 검색어가 바뀌어도 입력칸이 따라간다. */}
        <form key={keyword} className="join flex-1" onSubmit={handleSearch}>
          <input
            name="keyword"
            defaultValue={keyword}
            maxLength={100}
            placeholder="상품명이나 설명으로 검색"
            className="input input-bordered join-item w-full min-w-40"
            aria-label="검색어"
          />
          <button type="submit" className="btn btn-primary join-item">
            검색
          </button>
        </form>
        <select
          className="select select-bordered"
          value={category}
          aria-label="카테고리"
          onChange={(event) => update({ category: event.target.value })}
        >
          <option value="">전체 카테고리</option>
          {categories.map((item) => (
            <option key={item.categoryId} value={item.categoryId}>
              {item.name}
            </option>
          ))}
        </select>
        <select className="select select-bordered" value={sort} aria-label="정렬" onChange={(event) => update({ sort: event.target.value })}>
          {SORT_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>

      {loading && (
        <div className="flex justify-center py-16">
          <span className="loading loading-spinner loading-lg text-primary" />
        </div>
      )}
      {!loading && result.failed && <p className="py-16 text-center text-base-content/60">목록을 불러오지 못했습니다.</p>}

      {data !== null && data.content.length === 0 && page > 0 && data.totalElements > 0 && (
        <div className="py-16 text-center">
          <p className="text-base-content/60">해당 페이지에 경매가 없습니다.</p>
          <button type="button" className="btn btn-primary btn-sm mt-4" onClick={() => update({ page: 0 })}>
            첫 페이지로
          </button>
        </div>
      )}
      {data !== null && data.content.length === 0 && !(page > 0 && data.totalElements > 0) && (
        <p className="py-16 text-center text-base-content/60">
          {keyword || category ? '조건에 맞는 경매가 없습니다.' : '등록된 경매가 없습니다.'}
        </p>
      )}

      {data !== null && data.content.length > 0 && (
        <>
          <p className="mb-2 text-sm text-base-content/60">전체 {data.totalElements.toLocaleString('ko-KR')}건</p>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
            {data.content.map((auction) => (
              <AuctionCard key={auction.auctionId} auction={auction} />
            ))}
          </div>
        </>
      )}
      {data !== null && <Pagination page={page} totalPages={data.totalPages} onChange={(next) => update({ page: next })} />}
    </div>
  );
}

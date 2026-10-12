// 페이지 번호 이동. 현재 페이지 앞뒤로 두 칸씩만 보여 주고, 처음과 끝 페이지는 항상 보여 준다.
// page는 0부터 시작한다. onChange(nextPage)
export default function Pagination({ page, totalPages, onChange }) {
  if (totalPages <= 1) {
    return null;
  }

  const numbers = [];
  for (let i = 0; i < totalPages; i += 1) {
    if (i === 0 || i === totalPages - 1 || Math.abs(i - page) <= 2) {
      numbers.push(i);
    }
  }

  return (
    <div className="join mt-6 flex justify-center">
      <button type="button" className="btn btn-sm join-item" disabled={page === 0} onClick={() => onChange(page - 1)}>
        이전
      </button>
      {numbers.map((number, index) => (
        <span key={number} className="contents">
          {index > 0 && number - numbers[index - 1] > 1 && (
            <button type="button" className="btn btn-sm btn-disabled join-item">
              ...
            </button>
          )}
          <button
            type="button"
            className={`btn btn-sm join-item ${number === page ? 'btn-primary' : ''}`}
            aria-current={number === page ? 'page' : undefined}
            onClick={() => onChange(number)}
          >
            {number + 1}
          </button>
        </span>
      ))}
      <button type="button" className="btn btn-sm join-item" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
        다음
      </button>
    </div>
  );
}

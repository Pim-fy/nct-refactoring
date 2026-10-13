// 화면에 보여 줄 값의 형식을 맞추는 도우미

// 금액: 1234567 → "1,234,567원". 값이 없으면 "-"다.
export function formatWon(value) {
  if (value === null || value === undefined) {
    return '-';
  }
  return `${Number(value).toLocaleString('ko-KR')}원`;
}

// 날짜와 시각: "2026-10-12T21:00:00+09:00" → "2026.10.12 21:00" (한국 시간 기준)
export function formatDateTime(iso) {
  if (!iso) {
    return '-';
  }
  const parts = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(new Date(iso));
  const get = (type) => parts.find((part) => part.type === type)?.value ?? '';
  return `${get('year')}.${get('month')}.${get('day')} ${get('hour')}:${get('minute')}`;
}

// 남은 시간: 초를 "2일 3시간", "5시간 20분", "12분"처럼 보여 준다. 0 이하이면 종료 처리를 기다리는 상태다.
export function formatRemaining(seconds) {
  if (seconds <= 0) {
    return '마감(종료 처리 중)';
  }
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) {
    return `${days}일 ${hours}시간`;
  }
  if (hours > 0) {
    return `${hours}시간 ${minutes}분`;
  }
  return `${Math.max(minutes, 1)}분`;
}

// 경매 상태 코드를 화면 문구로 바꾼다. (기능 명세서 2-2)
export const AUCTION_STATUS_LABELS = {
  REGISTERED: '등록',
  IN_PROGRESS: '진행 중',
  SOLD: '낙찰',
  FAILED: '유찰',
  STOPPED: '중단',
};

// 상태별 배지 모양. Tailwind는 소스에 글자 그대로 있는 클래스만 만들므로 전체 이름을 적어 둔다.
export const AUCTION_STATUS_BADGES = {
  REGISTERED: 'badge-ghost',
  IN_PROGRESS: 'badge-primary',
  SOLD: 'badge-success',
  FAILED: 'badge-neutral',
  STOPPED: 'badge-error',
};

// 포인트: 1234567 → "1,234,567 P". 값이 없으면 "-"다.
export function formatPoint(value) {
  if (value === null || value === undefined) {
    return '-';
  }
  return `${Number(value).toLocaleString('ko-KR')} P`;
}

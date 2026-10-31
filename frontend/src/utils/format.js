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

// 거래 상태 코드를 화면 문구로 바꾼다. (기능 명세서 2-2)
export const TRADE_STATUS_LABELS = {
  READY: '준비',
  IN_PROGRESS: '진행',
  CONFIRMED: '구매 확정',
  COMPLETED: '완료',
  CANCELED: '취소',
  DISPUTED: '분쟁',
};

export const TRADE_STATUS_BADGES = {
  READY: 'badge-ghost',
  IN_PROGRESS: 'badge-primary',
  CONFIRMED: 'badge-info',
  COMPLETED: 'badge-success',
  CANCELED: 'badge-neutral',
  DISPUTED: 'badge-error',
};

export const TRADE_ROLE_LABELS = { SELLER: '판매', BUYER: '구매' };

// 신고 코드를 화면 문구로 바꾼다. (기능 명세서 3-9)
export const REPORT_REASON_LABELS = {
  FALSE_INFO: '허위·과장 정보',
  PROHIBITED_ITEM: '금지 물품',
  TRADE_MISCONDUCT: '거래 미이행·불성실',
  INAPPROPRIATE_OR_ETC: '부적절 행위·기타',
};

export const REPORT_TARGET_LABELS = { AUCTION: '경매', MEMBER: '회원', TRADE: '거래' };

export const REPORT_STATUS_LABELS = { RECEIVED: '접수', PROCESSED: '처리 완료', REJECTED: '반려' };

export const REPORT_STATUS_BADGES = { RECEIVED: 'badge-ghost', PROCESSED: 'badge-success', REJECTED: 'badge-neutral' };

// 처리 결과. 처리 사유와 처리한 관리자는 보여 주지 않는다.
export const REPORT_ACTION_LABELS = {
  AUCTION_STOPPED: '경매 중단',
  MEMBER_RESTRICTED: '회원 이용 제한',
  REJECTED: '반려',
};

// 회원 상태 코드를 화면 문구로 바꾼다. (기능 명세서 2-2)
export const MEMBER_STATUS_LABELS = {
  NORMAL: '정상',
  RESTRICTED_TRADE: '거래 제한',
  RESTRICTED_LOGIN: '로그인 정지',
  WITHDRAWN: '탈퇴',
};

export const MEMBER_STATUS_BADGES = {
  NORMAL: 'badge-success',
  RESTRICTED_TRADE: 'badge-warning',
  RESTRICTED_LOGIN: 'badge-error',
  WITHDRAWN: 'badge-neutral',
};

// 이용 제한 기간 문구. 영구이거나 "2026.10.06 00:00까지"다. 기간이 끝나도 관리자가 해제하기 전에는 제한이 유지된다.
export function formatRestrictionPeriod(restriction) {
  return restriction.isPermanent ? '영구' : `${formatDateTime(restriction.expiresAt)}까지`;
}

// 사유에 사람이 읽을 수 있는 글자가 하나 이상 있고 제어 문자(줄바꿈·탭 제외)가 없는지. 서버(ReasonText)와 같은 규칙이다.
// 공백·제어·서식 문자, 결합 문자(변형 선택자 등), 한글 채움 문자류와 점자 빈칸만으로 된 사유는 글자가 없는 것으로 본다.
export function isMeaningfulReason(text) {
  return (
    /[^\p{Z}\p{C}\p{M}ㅤᅟᅠﾠ⠀]/u.test(text) &&
    !/(?![\n\r\t])\p{Cc}/u.test(text) &&
    !/[‪-‮⁦-⁩]/u.test(text) // 글자 순서를 뒤집는 방향 전환 문자
  );
}

// 입력한 사유의 앞뒤 공백(NBSP·전각·제로폭·서식 문자와 한글 채움 문자 포함)을 서버와 같이 지운다.
export function trimReason(text) {
  return text.replace(/^[\p{Z}\s\p{Cf}ㅤ⠀]+|[\p{Z}\s\p{Cf}ㅤ⠀]+$/gu, '');
}

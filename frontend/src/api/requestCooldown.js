import { AxiosError } from 'axios';

// 서버의 중복 요청 방지와 짝을 이루는 화면 쪽 가드.
// 서버는 같은 사용자의 같은 쓰기 요청(메서드·주소·본문이 같음)이 성공하면 5초 동안 첫 응답을 그대로 돌려주고 실제로는 처리하지 않는다.
// 그래서 끄기 → 켜기 → 끄기, 추가 → 삭제 → 추가처럼 5초 안에 같은 요청으로 되돌리면 화면은 성공으로 보이는데 서버 상태는 바뀌지 않는다.
// 이를 막으려고, 성공한 쓰기 요청과 같은 요청은 서버 보관 시간보다 조금 긴 쿨다운 동안 보내지 않고 안내만 한다.
// 서버의 보관 시간(5초, API 명세서 0-4)을 바꾸면 이 값도 함께 바꾼다.
export const COOLDOWN_MS = 5500;

const WRITE_METHODS = new Set(['post', 'put', 'patch', 'delete']);

// 서버도 중복 요청 방지에서 제외한 API. 같은 요청을 다시 보내도 새로 처리해야 하므로 쿨다운을 적용하지 않는다.
const EXEMPT_URLS = ['/auth/login', '/auth/logout', '/auth/refresh'];

// 요청이 성공한 시각. 키는 `메서드 주소 본문`이다.
const lastSuccessAt = new Map();

// 요청 인터셉터에서는 본문이 객체이고, 응답 쪽 config에서는 이미 JSON 문자열이다.
// 두 경우 모두 같은 문자열이 되도록 맞춘다.
function keyOf(config) {
  const method = (config.method ?? 'get').toLowerCase();
  if (!WRITE_METHODS.has(method) || EXEMPT_URLS.includes(config.url)) {
    return null;
  }
  const data = config.data;
  if (typeof FormData !== 'undefined' && data instanceof FormData) {
    return null;   // 파일 업로드는 서버도 중복 요청 방지에서 제외한다.
  }
  const body = typeof data === 'string' ? data : JSON.stringify(data ?? null);
  return `${method} ${config.url} ${body} ${JSON.stringify(config.params ?? null)}`;
}

// 같은 요청이 성공한 뒤 쿨다운이 남은 시간(밀리초). 쿨다운 대상이 아니거나 지났으면 0이다.
export function cooldownRemainingMs(config) {
  const key = keyOf(config);
  if (key === null) return 0;
  const at = lastSuccessAt.get(key);
  if (at === undefined) return 0;
  return Math.max(0, COOLDOWN_MS - (Date.now() - at));
}

// 쓰기 요청이 성공하면 기록한다. 지난 기록은 함께 지운다.
export function recordSuccess(config) {
  const key = keyOf(config);
  if (key === null) return;
  const now = Date.now();
  for (const [recordedKey, at] of lastSuccessAt) {
    if (now - at >= COOLDOWN_MS) {
      lastSuccessAt.delete(recordedKey);
    }
  }
  lastSuccessAt.set(key, now);
}

// 서버 오류와 같은 모양(response.data.error.message)으로 만들어, 화면이 getErrorMessage로 그대로 안내할 수 있게 한다.
export function createCooldownError(config, remainingMs) {
  const seconds = Math.ceil(remainingMs / 1000);
  const message = `방금 같은 요청을 처리했습니다. ${seconds}초 뒤에 다시 시도해 주세요.`;
  return new AxiosError(message, 'ERR_REQUEST_COOLDOWN', config, null, {
    status: 429,
    statusText: 'Too Many Requests',
    headers: {},
    config,
    data: { success: false, data: null, error: { code: 'REQUEST_COOLDOWN', message } },
  });
}

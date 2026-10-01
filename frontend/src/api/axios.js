import axios from 'axios';

const BASE_URL = import.meta.env.VITE_API_BASE_URL;

/**
 * 백엔드 API 호출에 쓰는 공통 axios 인스턴스.
 * 액세스·리프레시 토큰 모두 HttpOnly 쿠키로 전달되므로,
 * 프론트엔드는 토큰 값을 직접 저장·관리하지 않는다.
 */
const api = axios.create({
  baseURL: BASE_URL,
  withCredentials: true,  // 브라우저가 쿠키를 요청에 자동으로 실어 보내게 함
});

// 401을 받아도 재발급을 시도하지 않는 요청.
// 로그인 실패(401)는 토큰 만료가 아니라 자격 불일치이고, 재발급·로그아웃은 재발급으로 해결되지 않는다.
const NO_REFRESH_URLS = ['/auth/login', '/auth/refresh', '/auth/logout'];

// 재발급까지 실패했을 때 로그인 상태를 정리하도록 AuthProvider가 등록하는 함수.
// 인터셉터가 화면을 직접 이동시키지 않는다. 이동은 라우터(회원 전용 경로 보호)가 맡는다.
let authFailureHandler = null;

export function setAuthFailureHandler(handler) {
  authFailureHandler = handler;
}

// 여러 요청이 동시에 401을 받아도 재발급은 한 번만 보내고 그 결과를 함께 기다린다.
let refreshPromise = null;

function refreshAccessToken() {
  if (!refreshPromise) {
    // `api`가 아닌 `axios`로 직접 호출한다. `api`로 보내면 이 응답 인터셉터를 다시 거쳐 무한 루프에 빠질 수 있다.
    refreshPromise = axios
      .post(`${BASE_URL}/auth/refresh`, {}, { withCredentials: true })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

// 인터셉터: 응답을 받은 뒤 가로채서 공통 처리를 끼워 넣음.
// 액세스 토큰 만료(401)를 감지해 재발급을 한 번 시도한 뒤 원래 요청을 재시도한다.
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;
    const isUnauthorized = error.response?.status === 401;
    const canRefresh =
      isUnauthorized &&
      originalRequest &&
      !originalRequest._retry &&
      !NO_REFRESH_URLS.includes(originalRequest.url);

    if (!canRefresh) {
      return Promise.reject(error);
    }

    originalRequest._retry = true;  // 한 요청을 두 번 이상 재시도하지 않도록 표시

    try {
      await refreshAccessToken();
      return api(originalRequest);  // 재발급에 성공. 원래 실패했던 요청을 다시 보냄.
    } catch {
      if (authFailureHandler) {
        authFailureHandler();
      }
      return Promise.reject(error);  // 호출한 쪽에는 원래 요청의 401을 그대로 알린다.
    }
  }
);

export default api;

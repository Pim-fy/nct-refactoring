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

// 인터셉터: 요청을 보내기 전, 응답을 받은 후에 가로채서 공통 처리를 끼워 넣음. 원래 흐름 중간에 끼어드는 것.
// 여기서는 액세스 토큰 만료(401)를 감지해 재발급을 한 번 시도한 뒤 원래 요청을 재시도한다.
api.interceptors.response.use(
  (response) => response, // 성공 시 콜백. 응답이 정상(2xx)이면 그대로 통과시킴.
  async (error) => {  // 실패 시 콜백.
    // error.config: 실패했던 요청 자체의 설정. 재발급에 성공하면 이 설정 그대로 요청을 다시 보내야 함.
    const originalRequest = error.config;

    // refresh 요청 자체가 실패한 경우는 재시도하지 않고 바로 로그인 화면으로 보낸다.
    // 무한 루프 1차 안전장치. 요청 자체가 "토큰 재발급 요청"이었다면, 더 재발급을 시도할 방법이 없으므로 로그인 페이지로 이동.
    if (originalRequest.url === '/auth/refresh') {
      window.location.href = '/login';
      return Promise.reject(error); // 에러 처리 후에도 호출한 쪽이 실패를 알 수 있도록 거절(reject) 유지
    }

    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true;  // 이후 재시도 시 알 수 있도록 _retry 속성을 붙임.

      try {
        // `api`가 아닌 `axios`로 직접 호출한다.
        // `api`로 보내면 이 응답 인터셉터를 다시 거치게 되어 무한 루프에 빠질 수 있다.
        await axios.post(`${BASE_URL}/auth/refresh`, {}, { withCredentials: true });  // 재발급 요청을 보내고, 서버 응답이 올 때까지 멈춰서 기다린다.
        return api(originalRequest);  // 재발급에 성공. 원래 실패했던 요청을 다시 보냄.
      } catch (refreshError) {  // 재발급 실패. 로그인 페이지로 보내고, 거절(reject)
        window.location.href = '/login';
        return Promise.reject(refreshError);
      }
    }

    // 401이 아니거나 이미 재시도한 요청이면 원래 에러를 그대로 거절(reject)
    return Promise.reject(error);
  }
);

export default api;

// 백엔드의 공통 응답 형식 { success, data, error }을 다루는 도우미. (기능 명세서 1-4)

const NETWORK_ERROR_MESSAGE = '서버에 연결할 수 없습니다. 네트워크를 확인해 주세요.';
const DEFAULT_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';

// axios 응답에서 data 부분만 꺼낸다.
export function unwrap(response) {
  return response.data.data;
}

// 공통 알림에 보여줄 문구. 서버가 준 message를 그대로 쓴다.
export function getErrorMessage(error) {
  const serverMessage = error?.response?.data?.error?.message;
  if (serverMessage) {
    return serverMessage;
  }
  return error?.response ? DEFAULT_ERROR_MESSAGE : NETWORK_ERROR_MESSAGE;
}

// 화면이 에러 코드로 처리를 나눌 때 쓴다.
export function getErrorCode(error) {
  return error?.response?.data?.error?.code;
}

// 입력값 오류의 필드별 사유 [{ field, message }]. 없으면 빈 배열.
export function getFieldErrors(error) {
  return error?.response?.data?.error?.details ?? [];
}

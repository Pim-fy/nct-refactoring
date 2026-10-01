import api from './axios';
import { unwrap } from './apiResponse';

// 시행 중인 약관 목록. 응답: { agreements: [{ agreementId, agreementType, version, title, content, isRequired }] }
export async function fetchAgreements() {
  return unwrap(await api.get('/agreements'));
}

export async function signUp(payload) {
  return unwrap(await api.post('/members', payload));
}

// 중복 확인. 응답: { isAvailable }
export async function checkLoginId(loginId) {
  return unwrap(await api.get('/members/check-login-id', { params: { loginId } }));
}

export async function checkNickname(nickname) {
  return unwrap(await api.get('/members/check-nickname', { params: { nickname } }));
}

// 로그인한 회원의 현재 정보. 비로그인이면 401이다.
export async function fetchMe() {
  return unwrap(await api.get('/members/me'));
}

import api from './axios';
import { unwrap } from './apiResponse';

// 로그인 성공 시 토큰은 쿠키로 저장되고, 본문에는 expiresIn과 member만 온다.
export async function login({ loginId, password, isKeepLogin }) {
  return unwrap(await api.post('/auth/login', { loginId, password, isKeepLogin }));
}

export async function logout() {
  return unwrap(await api.post('/auth/logout'));
}

// 이메일 인증번호 발송. 응답: { expiresAt }
export async function sendEmailCode({ email, purpose }) {
  return unwrap(await api.post('/auth/email-codes', { email, purpose }));
}

// 인증번호 검증. 응답: { verificationToken, tokenExpiresAt }
export async function verifyEmailCode({ email, purpose, code }) {
  return unwrap(await api.post('/auth/email-codes/verify', { email, purpose, code }));
}

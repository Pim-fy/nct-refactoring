import { createContext } from 'react';

// 값의 형태: { user, loading, login, logout }
// user는 GET /members/me 응답(로그인한 회원 정보)이고, 비로그인이면 null이다.
export const AuthContext = createContext(null);

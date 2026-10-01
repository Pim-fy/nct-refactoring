import { useCallback, useEffect, useMemo, useState } from 'react';
import { setAuthFailureHandler } from '../api/axios';
import * as authApi from '../api/authApi';
import { fetchMe } from '../api/memberApi';
import { AuthContext } from './authContext';

// 토큰이 HttpOnly 쿠키라 화면은 로그인 여부를 직접 알 수 없다.
// 그래서 앱이 시작될 때 GET /members/me로 서버에 물어 로그인 상태를 복원한다.
export default function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    fetchMe()
      .then((me) => {
        if (!cancelled) setUser(me);
      })
      .catch(() => {
        if (!cancelled) setUser(null);   // 비로그인이거나 이용할 수 없는 계정
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    // 토큰 재발급까지 실패하면 로그인 상태를 지운다. 이동은 회원 전용 경로 보호가 맡는다.
    setAuthFailureHandler(() => setUser(null));

    return () => {
      cancelled = true;
      setAuthFailureHandler(null);
    };
  }, []);

  const login = useCallback(async (credentials) => {
    await authApi.login(credentials);
    setUser(await fetchMe());   // 로그인 응답에는 요약만 있어 전체 정보를 다시 받는다.
  }, []);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      setUser(null);   // 서버 응답과 관계없이 화면의 로그인 상태는 지운다.
    }
  }, []);

  const value = useMemo(() => ({ user, loading, login, logout }), [user, loading, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

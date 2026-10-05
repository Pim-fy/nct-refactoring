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
    try {
      setUser(await fetchMe());   // 로그인 응답에는 요약만 있어 전체 정보를 다시 받는다.
    } catch (error) {
      // 서버에는 로그인(쿠키·리프레시 토큰)이 되었는데 화면은 비로그인인 상태로 남지 않게 서버 세션을 정리한다.
      await authApi.logout().catch(() => {});
      throw error;
    }
  }, []);

  // 서버에서 리프레시 토큰을 폐기하고 쿠키를 지운 뒤에야 화면의 로그인 상태를 지운다.
  // 서버 쪽 로그아웃이 실패했는데 화면만 로그아웃되면 새로고침 때 다시 로그인되므로, 실패는 호출한 쪽에 알린다.
  // 단, 401이면 재발급까지 실패해 서버 세션이 이미 없는 것이므로 로그아웃된 것으로 본다.
  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch (error) {
      if (error?.response?.status !== 401) {
        throw error;
      }
    }
    setUser(null);
  }, []);

  const value = useMemo(() => ({ user, loading, login, logout }), [user, loading, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

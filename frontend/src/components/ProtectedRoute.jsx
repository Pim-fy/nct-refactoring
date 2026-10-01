import { Spin } from 'antd';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

// 회원 전용 화면을 감싼다. 비회원이면 로그인 화면으로 보내고, 로그인 후 원래 화면으로 돌아오게 경로를 넘긴다.
// (화면 설계서 1장)
export default function ProtectedRoute() {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return <Spin fullscreen />;   // 로그인 상태를 확인하는 동안 기다린다.
  }
  if (!user) {
    const redirect = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?redirect=${redirect}`} replace />;
  }
  return <Outlet />;
}

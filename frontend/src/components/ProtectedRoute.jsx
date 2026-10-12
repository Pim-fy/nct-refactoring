import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

// 회원 전용 화면을 감싼다. 비회원이면 로그인 화면으로 보내고, 로그인 후 원래 화면으로 돌아오게 경로를 넘긴다.
// role을 주면 그 역할의 회원만 들어갈 수 있다. 다른 역할이면 홈으로 보낸다. (예: 관리자 화면 role="ADMIN")
// 서버도 같은 규칙으로 막으므로 이 검사는 화면 안내용이다. (화면 설계서 1장)
export default function ProtectedRoute({ role }) {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    // 로그인 상태를 확인하는 동안 기다린다.
    return (
      <div className="flex min-h-screen items-center justify-center">
        <span className="loading loading-spinner loading-lg text-primary" />
      </div>
    );
  }
  if (!user) {
    const redirect = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?redirect=${redirect}`} replace />;
  }
  if (role && user.memberRole !== role) {
    return <Navigate to="/" replace />;
  }
  return <Outlet />;
}

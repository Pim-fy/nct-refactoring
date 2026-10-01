import { Link, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { useToast } from '../hooks/useToast';

// 공통 화면 구조: 헤더 / 본문 / 푸터 (화면 설계서 1장)
// 카테고리와 검색창은 경매 목록을 만드는 2단계에서 헤더에 추가한다.
export default function Layout() {
  const { user, loading, logout } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();

  async function handleLogout() {
    await logout();
    toast.success('로그아웃되었습니다.');
    navigate('/');
  }

  return (
    <div className="flex min-h-screen flex-col bg-base-200">
      <header className="navbar bg-neutral px-6 text-neutral-content">
        <div className="flex-1">
          <Link to="/" className="text-xl font-bold">
            NCT
          </Link>
        </div>

        {/* 로그인 상태를 확인하는 동안에는 버튼이 깜빡이지 않도록 비워 둔다. */}
        {!loading && (
          <div className="flex items-center gap-2">
            {user ? (
              <>
                <span className="mr-2 text-sm">{user.nickname}님</span>
                <button type="button" className="btn btn-sm" onClick={handleLogout}>
                  로그아웃
                </button>
              </>
            ) : (
              <>
                <Link to="/login" className="btn btn-sm">
                  로그인
                </Link>
                <Link to="/signup" className="btn btn-sm btn-primary">
                  회원가입
                </Link>
              </>
            )}
          </div>
        )}
      </header>

      <main className="flex-1 p-6">
        <Outlet />
      </main>

      <footer className="p-4 text-center text-sm text-base-content/60">
        NCT 경매 서비스 · 학습·포트폴리오용 프로젝트
      </footer>
    </div>
  );
}

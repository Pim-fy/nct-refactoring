import { App, Button, Layout as AntLayout, Space, Typography } from 'antd';
import { Link, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

const { Header, Content, Footer } = AntLayout;

// 공통 화면 구조: 헤더 / 본문 / 푸터 (화면 설계서 1장)
// 카테고리와 검색창은 경매 목록을 만드는 2단계에서 헤더에 추가한다.
export default function Layout() {
  const { user, loading, logout } = useAuth();
  const { message } = App.useApp();
  const navigate = useNavigate();

  async function handleLogout() {
    await logout();
    message.success('로그아웃되었습니다.');
    navigate('/');
  }

  return (
    <AntLayout style={{ minHeight: '100vh' }}>
      <Header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <Link to="/" style={{ color: '#fff', fontSize: 20, fontWeight: 700 }}>
          NCT
        </Link>

        {/* 로그인 상태를 확인하는 동안에는 버튼이 깜빡이지 않도록 비워 둔다. */}
        {!loading && (
          <Space>
            {user ? (
              <>
                <Typography.Text style={{ color: '#fff' }}>{user.nickname}님</Typography.Text>
                <Button onClick={handleLogout}>로그아웃</Button>
              </>
            ) : (
              <>
                <Link to="/login">
                  <Button>로그인</Button>
                </Link>
                <Link to="/signup">
                  <Button type="primary">회원가입</Button>
                </Link>
              </>
            )}
          </Space>
        )}
      </Header>

      <Content style={{ padding: 24 }}>
        <Outlet />
      </Content>

      <Footer style={{ textAlign: 'center' }}>NCT 경매 서비스 · 학습·포트폴리오용 프로젝트</Footer>
    </AntLayout>
  );
}

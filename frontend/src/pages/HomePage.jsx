import { Empty, Typography } from 'antd';
import { useAuth } from '../hooks/useAuth';

// 홈·경매 목록은 2단계에서 만든다. 지금은 로그인 흐름을 확인할 수 있는 임시 화면이다.
export default function HomePage() {
  const { user } = useAuth();

  return (
    <Empty
      description={
        <>
          <Typography.Title level={4}>경매 목록은 2단계에서 구현됩니다.</Typography.Title>
          {user && <Typography.Text>{user.nickname}님, 로그인되었습니다.</Typography.Text>}
        </>
      }
    />
  );
}

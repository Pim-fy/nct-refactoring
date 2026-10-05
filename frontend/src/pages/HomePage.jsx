import { useAuth } from '../hooks/useAuth';

// 홈·경매 목록은 2단계에서 만든다. 지금은 로그인 흐름을 확인할 수 있는 임시 화면이다.
export default function HomePage() {
  const { user } = useAuth();

  return (
    <div className="mx-auto mt-24 max-w-md text-center">
      <h1 className="text-xl font-bold">경매 목록은 2단계에서 구현됩니다.</h1>
      {user && <p className="mt-2 text-base-content/70">{user.nickname}님, 로그인되었습니다.</p>}
    </div>
  );
}

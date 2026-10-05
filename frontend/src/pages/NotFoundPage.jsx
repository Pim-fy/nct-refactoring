import { Link } from 'react-router-dom';

export default function NotFoundPage() {
  return (
    <div className="mx-auto mt-24 max-w-md text-center">
      <p className="text-5xl font-bold text-primary">404</p>
      <h1 className="mt-4 text-xl font-bold">페이지를 찾을 수 없습니다.</h1>
      <Link to="/" className="btn btn-primary mt-6">
        홈으로
      </Link>
    </div>
  );
}

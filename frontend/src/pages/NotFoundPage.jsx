import { Button, Result } from 'antd';
import { Link } from 'react-router-dom';

export default function NotFoundPage() {
  return (
    <Result
      status="404"
      title="페이지를 찾을 수 없습니다."
      extra={
        <Link to="/">
          <Button type="primary">홈으로</Button>
        </Link>
      }
    />
  );
}

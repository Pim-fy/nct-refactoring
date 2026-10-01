import { useState } from 'react';
import { App, Button, Card, Checkbox, Form, Input, Typography } from 'antd';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { getErrorMessage } from '../api/apiResponse';
import { useAuth } from '../hooks/useAuth';

const SAVED_LOGIN_ID_KEY = 'nct.savedLoginId';

// 아이디 저장은 로그인 ID만 브라우저에 둔다. 비밀번호와 토큰은 저장하지 않는다. (기능 명세서 3-2 로그인 옵션)
// 저장소를 쓸 수 없는 환경(비공개 창 등)에서도 로그인은 되도록 예외를 삼킨다.
function readSavedLoginId() {
  try {
    return localStorage.getItem(SAVED_LOGIN_ID_KEY) ?? '';
  } catch {
    return '';
  }
}

function writeSavedLoginId(loginId) {
  try {
    if (loginId) {
      localStorage.setItem(SAVED_LOGIN_ID_KEY, loginId);
    } else {
      localStorage.removeItem(SAVED_LOGIN_ID_KEY);
    }
  } catch {
    // 저장하지 못해도 로그인에는 영향이 없다.
  }
}

// 로그인 뒤 돌아갈 경로. 같은 사이트의 내부 경로만 허용한다. (//evil.com 같은 값으로 다른 사이트로 보내지 못하게 한다.)
function safeRedirect(value) {
  return value && value.startsWith('/') && !value.startsWith('//') ? value : '/';
}

export default function LoginPage() {
  const { user, loading, login } = useAuth();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [submitting, setSubmitting] = useState(false);
  const [initialLoginId] = useState(readSavedLoginId);

  const redirectTo = safeRedirect(searchParams.get('redirect'));

  // 이미 로그인한 상태로 들어오면 원래 가려던 곳으로 보낸다.
  if (!loading && user) {
    return <Navigate to={redirectTo} replace />;
  }

  async function handleFinish(values) {
    setSubmitting(true);   // 제출하는 동안 버튼을 잠가 중복 전송을 줄인다.
    try {
      await login({
        loginId: values.loginId,
        password: values.password,
        isKeepLogin: Boolean(values.keepLogin),
      });
      writeSavedLoginId(values.saveLoginId ? values.loginId : '');
      navigate(redirectTo, { replace: true });
    } catch (error) {
      message.error(getErrorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Card style={{ maxWidth: 400, margin: '48px auto' }}>
      <Typography.Title level={3} style={{ textAlign: 'center' }}>
        로그인
      </Typography.Title>

      <Form
        layout="vertical"
        onFinish={handleFinish}
        initialValues={{ loginId: initialLoginId, saveLoginId: Boolean(initialLoginId), keepLogin: false }}
      >
        <Form.Item label="아이디" name="loginId" rules={[{ required: true, message: '아이디를 입력해 주세요.' }]}>
          <Input autoComplete="username" />
        </Form.Item>

        <Form.Item label="비밀번호" name="password" rules={[{ required: true, message: '비밀번호를 입력해 주세요.' }]}>
          <Input.Password autoComplete="current-password" />
        </Form.Item>

        <Form.Item>
          <Form.Item name="keepLogin" valuePropName="checked" noStyle>
            <Checkbox>로그인 유지</Checkbox>
          </Form.Item>
          <Form.Item name="saveLoginId" valuePropName="checked" noStyle>
            <Checkbox>아이디 저장</Checkbox>
          </Form.Item>
        </Form.Item>

        <Button type="primary" htmlType="submit" block loading={submitting}>
          로그인
        </Button>
      </Form>

      <div style={{ marginTop: 16, textAlign: 'center' }}>
        아직 회원이 아니신가요? <Link to="/signup">회원가입</Link>
      </div>
    </Card>
  );
}

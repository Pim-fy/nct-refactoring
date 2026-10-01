import { useEffect, useMemo, useState } from 'react';
import { App, Alert, Button, Card, Checkbox, Divider, Form, Input, Modal, Space, Typography } from 'antd';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { getErrorCode, getErrorMessage, getFieldErrors } from '../api/apiResponse';
import { sendEmailCode, verifyEmailCode } from '../api/authApi';
import { checkLoginId, checkNickname, fetchAgreements, signUp } from '../api/memberApi';
import { useAuth } from '../hooks/useAuth';

// 입력 형식은 기능 명세서 3-1을 따른다. 서버가 가입 요청에서 다시 검사한다.
const LOGIN_ID_PATTERN = /^[A-Za-z0-9]{6,20}$/;
const NICKNAME_PATTERN = /^[가-힣A-Za-z0-9]{2,10}$/;
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).{8,}$/;
const PHONE_PATTERN = /^[0-9-]{9,20}$/;
const BCRYPT_MAX_BYTES = 72;   // 서버가 비밀번호를 암호화할 때의 한계

// 서버 오류 코드를 어느 입력란의 오류로 보여줄지
const DUPLICATE_CODE_TO_FIELD = {
  MEMBER_ID_DUPLICATED: 'loginId',
  MEMBER_NICKNAME_DUPLICATED: 'nickname',
  MEMBER_EMAIL_DUPLICATED: 'email',
};
const FORM_FIELDS = ['loginId', 'password', 'nickname', 'email', 'phone'];

function formatRemaining(seconds) {
  const m = String(Math.floor(seconds / 60)).padStart(2, '0');
  const s = String(seconds % 60).padStart(2, '0');
  return `${m}:${s}`;
}

export default function SignupPage() {
  const { user, loading } = useAuth();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [form] = Form.useForm();

  // 약관
  const [agreements, setAgreements] = useState([]);
  const [agreed, setAgreed] = useState({});   // { [agreementId]: boolean }
  const [viewing, setViewing] = useState(null);

  // 중복 확인 결과: { value, available } — 확인한 값과 같을 때만 유효하다.
  const [loginIdCheck, setLoginIdCheck] = useState(null);
  const [nicknameCheck, setNicknameCheck] = useState(null);

  // 이메일 인증: 발송 → 입력 → 확인(검증 토큰) 순서
  const [codeExpiresAt, setCodeExpiresAt] = useState(null);   // 인증번호 만료 시각(ms). 발송 전이면 null
  const [now, setNow] = useState(() => Date.now());
  const [verificationToken, setVerificationToken] = useState(null);
  const [sendingCode, setSendingCode] = useState(false);
  const [verifyingCode, setVerifyingCode] = useState(false);

  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchAgreements()
      .then((data) => {
        if (!cancelled) setAgreements(data.agreements);
      })
      .catch((error) => message.error(getErrorMessage(error)));
    return () => {
      cancelled = true;
    };
  }, [message]);

  // 인증번호 남은 시간을 1초마다 갱신한다. 인증이 끝났거나 아직 발송 전이면 돌리지 않는다.
  const counting = codeExpiresAt !== null && verificationToken === null;
  useEffect(() => {
    if (!counting) return undefined;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [counting]);

  const remainingSeconds = codeExpiresAt === null ? 0 : Math.max(0, Math.ceil((codeExpiresAt - now) / 1000));
  const codeExpired = codeExpiresAt !== null && remainingSeconds === 0;
  const emailLocked = verificationToken !== null;

  const allRequiredAgreed = useMemo(
    () => agreements.length > 0 && agreements.filter((a) => a.isRequired).every((a) => agreed[a.agreementId]),
    [agreements, agreed]
  );
  const allAgreed = agreements.length > 0 && agreements.every((a) => agreed[a.agreementId]);

  if (!loading && user) {
    return <Navigate to="/" replace />;
  }

  function toggleAll(checked) {
    setAgreed(Object.fromEntries(agreements.map((a) => [a.agreementId, checked])));
  }

  async function handleCheckLoginId() {
    try {
      await form.validateFields(['loginId']);
      const value = form.getFieldValue('loginId');
      const { isAvailable } = await checkLoginId(value);
      setLoginIdCheck({ value, available: isAvailable });
    } catch (error) {
      if (error?.response) message.error(getErrorMessage(error));   // 형식 오류는 입력란 메시지로 이미 보인다.
    }
  }

  async function handleCheckNickname() {
    try {
      await form.validateFields(['nickname']);
      const value = form.getFieldValue('nickname');
      const { isAvailable } = await checkNickname(value);
      setNicknameCheck({ value, available: isAvailable });
    } catch (error) {
      if (error?.response) message.error(getErrorMessage(error));
    }
  }

  async function handleSendCode() {
    try {
      await form.validateFields(['email']);
    } catch {
      return;   // 이메일 형식 오류는 입력란에 표시된다.
    }
    setSendingCode(true);
    try {
      const { expiresAt } = await sendEmailCode({ email: form.getFieldValue('email'), purpose: 'SIGN_UP' });
      form.setFieldValue('code', '');
      setNow(Date.now());
      setCodeExpiresAt(new Date(expiresAt).getTime());
      message.success('인증번호를 보냈습니다. 메일을 확인해 주세요.');
    } catch (error) {
      message.error(getErrorMessage(error));
    } finally {
      setSendingCode(false);
    }
  }

  async function handleVerifyCode() {
    try {
      await form.validateFields(['code']);
    } catch {
      return;
    }
    setVerifyingCode(true);
    try {
      const { verificationToken: token } = await verifyEmailCode({
        email: form.getFieldValue('email'),
        purpose: 'SIGN_UP',
        code: form.getFieldValue('code'),
      });
      setVerificationToken(token);   // 이후 이메일 입력란은 잠기고, 가입 요청에 이 토큰을 함께 보낸다.
      message.success('이메일 인증이 완료되었습니다.');
    } catch (error) {
      message.error(getErrorMessage(error));
    } finally {
      setVerifyingCode(false);
    }
  }

  // 인증 토큰이 거부됐을 때 다시 인증받을 수 있게 인증 상태를 처음으로 돌린다.
  function resetEmailVerification() {
    setVerificationToken(null);
    setCodeExpiresAt(null);
    form.setFieldValue('code', '');
  }

  function showServerErrors(error) {
    const code = getErrorCode(error);

    if (code === 'VERIFICATION_TOKEN_INVALID') {
      resetEmailVerification();
    }
    // 중복 오류는 해당 입력란에 보여준다.
    if (DUPLICATE_CODE_TO_FIELD[code]) {
      form.setFields([{ name: DUPLICATE_CODE_TO_FIELD[code], errors: [getErrorMessage(error)] }]);
      return;
    }
    // 입력값 오류는 필드별 사유를 각 입력란에 보여준다.
    const details = getFieldErrors(error);
    const fieldErrors = details.filter((d) => FORM_FIELDS.includes(d.field));
    if (fieldErrors.length > 0) {
      form.setFields(fieldErrors.map((d) => ({ name: d.field, errors: [d.message] })));
    }
    const others = details.filter((d) => !FORM_FIELDS.includes(d.field));
    if (fieldErrors.length === 0 || others.length > 0) {
      message.error(others[0]?.message ?? getErrorMessage(error));
    }
  }

  async function handleFinish(values) {
    if (!verificationToken) {
      message.warning('이메일 인증을 완료해 주세요.');
      return;
    }
    setSubmitting(true);   // 제출하는 동안 버튼을 잠가 중복 전송을 줄인다.
    try {
      await signUp({
        loginId: values.loginId,
        password: values.password,
        nickname: values.nickname,
        email: values.email,
        phone: values.phone,
        verificationToken,
        // 선택 약관에 동의하지 않은 이력도 함께 보낸다.
        agreements: agreements.map((a) => ({ agreementId: a.agreementId, isAgreed: Boolean(agreed[a.agreementId]) })),
      });
      message.success('가입이 완료되었습니다. 로그인해 주세요.');
      navigate('/login');
    } catch (error) {
      showServerErrors(error);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Card style={{ maxWidth: 520, margin: '24px auto' }}>
      <Typography.Title level={3} style={{ textAlign: 'center' }}>
        회원가입
      </Typography.Title>

      <Form form={form} layout="vertical" onFinish={handleFinish} requiredMark={false}>
        <Form.Item
          label="로그인 ID"
          required
          validateStatus={loginIdCheck && loginIdCheck.value === form.getFieldValue('loginId') ? (loginIdCheck.available ? 'success' : 'error') : undefined}
          help={loginIdCheck && loginIdCheck.value === form.getFieldValue('loginId') ? (loginIdCheck.available ? '사용할 수 있는 아이디입니다.' : '이미 사용 중인 아이디입니다.') : undefined}
        >
          <Space.Compact style={{ width: '100%' }}>
            <Form.Item
              name="loginId"
              noStyle
              rules={[
                { required: true, message: '로그인 ID를 입력해 주세요.' },
                { pattern: LOGIN_ID_PATTERN, message: '영문과 숫자로 6~20자여야 합니다.' },
              ]}
            >
              <Input autoComplete="username" />
            </Form.Item>
            <Button onClick={handleCheckLoginId}>중복 확인</Button>
          </Space.Compact>
        </Form.Item>

        <Form.Item
          label="비밀번호"
          name="password"
          rules={[
            { required: true, message: '비밀번호를 입력해 주세요.' },
            { pattern: PASSWORD_PATTERN, message: '8자 이상이며 영문과 숫자를 포함해야 합니다.' },
            {
              validator: (_, value) =>
                !value || new TextEncoder().encode(value).length <= BCRYPT_MAX_BYTES
                  ? Promise.resolve()
                  : Promise.reject(new Error('비밀번호가 너무 깁니다. 영문·숫자 기준 72자(한글은 24자) 이하여야 합니다.')),
            },
          ]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>

        <Form.Item
          label="비밀번호 확인"
          name="passwordConfirm"
          dependencies={['password']}
          rules={[
            { required: true, message: '비밀번호를 한 번 더 입력해 주세요.' },
            ({ getFieldValue }) => ({
              validator: (_, value) =>
                !value || getFieldValue('password') === value
                  ? Promise.resolve()
                  : Promise.reject(new Error('비밀번호가 일치하지 않습니다.')),
            }),
          ]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>

        <Form.Item
          label="닉네임"
          required
          validateStatus={nicknameCheck && nicknameCheck.value === form.getFieldValue('nickname') ? (nicknameCheck.available ? 'success' : 'error') : undefined}
          help={nicknameCheck && nicknameCheck.value === form.getFieldValue('nickname') ? (nicknameCheck.available ? '사용할 수 있는 닉네임입니다.' : '이미 사용 중인 닉네임입니다.') : undefined}
        >
          <Space.Compact style={{ width: '100%' }}>
            <Form.Item
              name="nickname"
              noStyle
              rules={[
                { required: true, message: '닉네임을 입력해 주세요.' },
                { pattern: NICKNAME_PATTERN, message: '한글, 영문, 숫자로 2~10자여야 합니다.' },
              ]}
            >
              <Input />
            </Form.Item>
            <Button onClick={handleCheckNickname}>중복 확인</Button>
          </Space.Compact>
        </Form.Item>

        <Form.Item
          label="연락처"
          name="phone"
          rules={[
            { required: true, message: '연락처를 입력해 주세요.' },
            { pattern: PHONE_PATTERN, message: '숫자와 하이픈으로 9~20자여야 합니다.' },
          ]}
        >
          <Input placeholder="010-1234-5678" autoComplete="tel" />
        </Form.Item>

        <Divider />

        <Form.Item label="이메일" required>
          <Space.Compact style={{ width: '100%' }}>
            <Form.Item
              name="email"
              noStyle
              rules={[
                { required: true, message: '이메일을 입력해 주세요.' },
                { type: 'email', message: '이메일 형식이 올바르지 않습니다.' },
                { max: 100, message: '이메일은 100자 이하여야 합니다.' },
              ]}
            >
              <Input disabled={emailLocked} autoComplete="email" />
            </Form.Item>
            <Button onClick={handleSendCode} loading={sendingCode} disabled={emailLocked || (codeExpiresAt !== null && !codeExpired)}>
              {codeExpired ? '재발송' : '인증번호 받기'}
            </Button>
          </Space.Compact>
        </Form.Item>

        {codeExpiresAt !== null && !emailLocked && (
          <Form.Item
            label="인증번호"
            extra={
              codeExpired ? (
                <Typography.Text type="danger">인증번호 유효 시간이 지났습니다. 재발송해 주세요.</Typography.Text>
              ) : (
                <Typography.Text type="secondary">남은 시간 {formatRemaining(remainingSeconds)}</Typography.Text>
              )
            }
          >
            <Space.Compact style={{ width: '100%' }}>
              <Form.Item
                name="code"
                noStyle
                rules={[
                  { required: true, message: '인증번호를 입력해 주세요.' },
                  { pattern: /^\d{6}$/, message: '6자리 숫자를 입력해 주세요.' },
                ]}
              >
                <Input maxLength={6} inputMode="numeric" disabled={codeExpired} />
              </Form.Item>
              <Button onClick={handleVerifyCode} loading={verifyingCode} disabled={codeExpired}>
                확인
              </Button>
            </Space.Compact>
          </Form.Item>
        )}

        {emailLocked && <Alert type="success" showIcon message="이메일 인증이 완료되었습니다." style={{ marginBottom: 24 }} />}

        <Divider />

        <Form.Item label="약관 동의" required>
          <Checkbox checked={allAgreed} onChange={(e) => toggleAll(e.target.checked)}>
            전체 동의
          </Checkbox>
          <Divider style={{ margin: '12px 0' }} />
          {agreements.map((a) => (
            <div key={a.agreementId} style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
              <Checkbox
                checked={Boolean(agreed[a.agreementId])}
                onChange={(e) => setAgreed((prev) => ({ ...prev, [a.agreementId]: e.target.checked }))}
              >
                ({a.isRequired ? '필수' : '선택'}) {a.title}
              </Checkbox>
              <Button type="link" size="small" onClick={() => setViewing(a)}>
                보기
              </Button>
            </div>
          ))}
        </Form.Item>

        <Button type="primary" htmlType="submit" block loading={submitting} disabled={!allRequiredAgreed}>
          가입하기
        </Button>
      </Form>

      <div style={{ marginTop: 16, textAlign: 'center' }}>
        이미 회원이신가요? <Link to="/login">로그인</Link>
      </div>

      <Modal
        open={viewing !== null}
        title={viewing?.title}
        onCancel={() => setViewing(null)}
        footer={<Button onClick={() => setViewing(null)}>닫기</Button>}
      >
        <div className="pre-wrap">{viewing?.content}</div>
      </Modal>
    </Card>
  );
}

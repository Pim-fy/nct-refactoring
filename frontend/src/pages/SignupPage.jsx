import { useEffect, useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { getErrorCode, getErrorMessage, getFieldErrors } from '../api/apiResponse';
import { sendEmailCode, verifyEmailCode } from '../api/authApi';
import { checkLoginId, checkNickname, fetchAgreements, signUp } from '../api/memberApi';
import FormField from '../components/FormField';
import Modal from '../components/Modal';
import { useAuth } from '../hooks/useAuth';
import { useToast } from '../hooks/useToast';

// 입력 형식은 기능 명세서 3-1을 따른다. 서버가 가입 요청에서 다시 검사한다.
const LOGIN_ID_PATTERN = /^[A-Za-z0-9]{6,20}$/;
const NICKNAME_PATTERN = /^[가-힣A-Za-z0-9]{2,10}$/;
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).{8,}$/;
const PHONE_PATTERN = /^[0-9-]{9,20}$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
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

// 입력란 + 오른쪽 버튼을 한 줄로 붙인다.
function InputWithButton({ input, buttonLabel, onClick, loading = false, disabled = false }) {
  return (
    <div className="join w-full">
      <div className="flex-1">{input}</div>
      <button type="button" className="btn join-item" onClick={onClick} disabled={disabled || loading}>
        {loading && <span className="loading loading-spinner loading-xs" />}
        {buttonLabel}
      </button>
    </div>
  );
}

export default function SignupPage() {
  const { user, loading } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();

  const {
    register,
    handleSubmit,
    trigger,
    getValues,
    setValue,
    setError,
    control,
    formState: { errors, isSubmitting },   // isSubmitting은 제출이 끝날 때까지 true라 버튼을 잠가 중복 전송을 줄인다.
  } = useForm({
    mode: 'onTouched',
    defaultValues: { loginId: '', password: '', passwordConfirm: '', nickname: '', phone: '', email: '', code: '' },
  });

  // 약관
  const [agreements, setAgreements] = useState([]);
  const [agreed, setAgreed] = useState({});   // { [agreementId]: boolean }
  const [viewing, setViewing] = useState(null);

  // 중복 확인 결과: { value, available } — 확인한 값과 지금 입력값이 같을 때만 보여준다.
  const [loginIdCheck, setLoginIdCheck] = useState(null);
  const [nicknameCheck, setNicknameCheck] = useState(null);

  // 이메일 인증: 발송 → 입력 → 확인(검증 토큰) 순서
  const [codeExpiresAt, setCodeExpiresAt] = useState(null);   // 인증번호 만료 시각(ms). 발송 전이면 null
  const [now, setNow] = useState(() => Date.now());
  const [verificationToken, setVerificationToken] = useState(null);
  const [sendingCode, setSendingCode] = useState(false);
  const [verifyingCode, setVerifyingCode] = useState(false);

  const loginIdValue = useWatch({ control, name: 'loginId' });
  const nicknameValue = useWatch({ control, name: 'nickname' });

  useEffect(() => {
    let cancelled = false;
    fetchAgreements()
      .then((data) => {
        if (!cancelled) setAgreements(data.agreements);
      })
      .catch((error) => {
        if (!cancelled) toast.error(getErrorMessage(error));
      });
    return () => {
      cancelled = true;
    };
  }, [toast]);

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
    if (!(await trigger('loginId'))) return;   // 형식 오류는 입력란 아래에 표시된다.
    const value = getValues('loginId');
    try {
      const { isAvailable } = await checkLoginId(value);
      setLoginIdCheck({ value, available: isAvailable });
    } catch (error) {
      toast.error(getErrorMessage(error));
    }
  }

  async function handleCheckNickname() {
    if (!(await trigger('nickname'))) return;
    const value = getValues('nickname');
    try {
      const { isAvailable } = await checkNickname(value);
      setNicknameCheck({ value, available: isAvailable });
    } catch (error) {
      toast.error(getErrorMessage(error));
    }
  }

  async function handleSendCode() {
    if (!(await trigger('email'))) return;
    setSendingCode(true);
    try {
      const { expiresAt } = await sendEmailCode({ email: getValues('email'), purpose: 'SIGN_UP' });
      setValue('code', '');
      setNow(Date.now());
      setCodeExpiresAt(new Date(expiresAt).getTime());
      toast.success('인증번호를 보냈습니다. 메일을 확인해 주세요.');
    } catch (error) {
      toast.error(getErrorMessage(error));
    } finally {
      setSendingCode(false);
    }
  }

  async function handleVerifyCode() {
    if (!(await trigger('code'))) return;
    setVerifyingCode(true);
    try {
      const { verificationToken: token } = await verifyEmailCode({
        email: getValues('email'),
        purpose: 'SIGN_UP',
        code: getValues('code'),
      });
      setVerificationToken(token);   // 이후 이메일 입력란은 잠기고, 가입 요청에 이 토큰을 함께 보낸다.
      toast.success('이메일 인증이 완료되었습니다.');
    } catch (error) {
      toast.error(getErrorMessage(error));
    } finally {
      setVerifyingCode(false);
    }
  }

  // 인증 토큰이 거부됐거나 이메일을 바꿔야 할 때 인증 상태를 처음으로 돌린다.
  function resetEmailVerification() {
    setVerificationToken(null);
    setCodeExpiresAt(null);
    setValue('code', '');
  }

  function showServerErrors(error) {
    const code = getErrorCode(error);

    // 인증은 끝났지만 이메일이 이미 쓰이고 있으면 이메일을 바꿀 수 있게 잠금을 푼다.
    if (code === 'VERIFICATION_TOKEN_INVALID' || code === 'MEMBER_EMAIL_DUPLICATED') {
      resetEmailVerification();
    }
    // 중복 오류는 해당 입력란에 보여준다.
    if (DUPLICATE_CODE_TO_FIELD[code]) {
      setError(DUPLICATE_CODE_TO_FIELD[code], { type: 'server', message: getErrorMessage(error) });
      return;
    }
    // 입력값 오류는 필드별 사유를 각 입력란에 보여준다.
    const details = getFieldErrors(error);
    const fieldErrors = details.filter((d) => FORM_FIELDS.includes(d.field));
    fieldErrors.forEach((d) => setError(d.field, { type: 'server', message: d.message }));
    const others = details.filter((d) => !FORM_FIELDS.includes(d.field));
    if (fieldErrors.length === 0 || others.length > 0) {
      toast.error(others[0]?.message ?? getErrorMessage(error));
    }
  }

  async function onSubmit(values) {
    if (!verificationToken) {
      toast.warning('이메일 인증을 완료해 주세요.');
      return;
    }
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
      toast.success('가입이 완료되었습니다. 로그인해 주세요.');
      navigate('/login');
    } catch (error) {
      showServerErrors(error);
    }
  }

  const inputClass = (name) => `input w-full ${errors[name] ? 'input-error' : ''}`;
  const checkMessage = (check, current, okText, ngText) =>
    check && check.value === current ? { success: check.available ? okText : undefined, error: check.available ? undefined : ngText } : {};

  return (
    <div className="card mx-auto my-6 max-w-lg bg-base-100 shadow-sm">
      <div className="card-body">
        <h1 className="mb-2 text-center text-2xl font-bold">회원가입</h1>

        <form onSubmit={handleSubmit(onSubmit)} noValidate>
          <FormField
            label="로그인 ID"
            htmlFor="loginId"
            error={errors.loginId?.message ?? checkMessage(loginIdCheck, loginIdValue, '', '이미 사용 중인 아이디입니다.').error}
            success={checkMessage(loginIdCheck, loginIdValue, '사용할 수 있는 아이디입니다.', '').success}
          >
            <InputWithButton
              buttonLabel="중복 확인"
              onClick={handleCheckLoginId}
              input={
                <input
                  id="loginId"
                  autoComplete="username"
                  className={`${inputClass('loginId')} join-item`}
                  {...register('loginId', {
                    required: '로그인 ID를 입력해 주세요.',
                    pattern: { value: LOGIN_ID_PATTERN, message: '영문과 숫자로 6~20자여야 합니다.' },
                  })}
                />
              }
            />
          </FormField>

          <FormField label="비밀번호" htmlFor="password" error={errors.password?.message}>
            <input
              id="password"
              type="password"
              autoComplete="new-password"
              className={inputClass('password')}
              {...register('password', {
                required: '비밀번호를 입력해 주세요.',
                pattern: { value: PASSWORD_PATTERN, message: '8자 이상이며 영문과 숫자를 포함해야 합니다.' },
                validate: (value) =>
                  new TextEncoder().encode(value).length <= BCRYPT_MAX_BYTES ||
                  '비밀번호가 너무 깁니다. 영문·숫자 기준 72자(한글은 24자) 이하여야 합니다.',
                deps: ['passwordConfirm'],   // 비밀번호를 고치면 확인란도 다시 검사한다.
              })}
            />
          </FormField>

          <FormField label="비밀번호 확인" htmlFor="passwordConfirm" error={errors.passwordConfirm?.message}>
            <input
              id="passwordConfirm"
              type="password"
              autoComplete="new-password"
              className={inputClass('passwordConfirm')}
              {...register('passwordConfirm', {
                required: '비밀번호를 한 번 더 입력해 주세요.',
                validate: (value) => value === getValues('password') || '비밀번호가 일치하지 않습니다.',
              })}
            />
          </FormField>

          <FormField
            label="닉네임"
            htmlFor="nickname"
            error={errors.nickname?.message ?? checkMessage(nicknameCheck, nicknameValue, '', '이미 사용 중인 닉네임입니다.').error}
            success={checkMessage(nicknameCheck, nicknameValue, '사용할 수 있는 닉네임입니다.', '').success}
          >
            <InputWithButton
              buttonLabel="중복 확인"
              onClick={handleCheckNickname}
              input={
                <input
                  id="nickname"
                  className={`${inputClass('nickname')} join-item`}
                  {...register('nickname', {
                    required: '닉네임을 입력해 주세요.',
                    pattern: { value: NICKNAME_PATTERN, message: '한글, 영문, 숫자로 2~10자여야 합니다.' },
                  })}
                />
              }
            />
          </FormField>

          <FormField label="연락처" htmlFor="phone" error={errors.phone?.message}>
            <input
              id="phone"
              autoComplete="tel"
              placeholder="010-1234-5678"
              className={inputClass('phone')}
              {...register('phone', {
                required: '연락처를 입력해 주세요.',
                pattern: { value: PHONE_PATTERN, message: '숫자와 하이픈으로 9~20자여야 합니다.' },
              })}
            />
          </FormField>

          <div className="divider" />

          <FormField label="이메일" htmlFor="email" error={errors.email?.message}>
            <InputWithButton
              buttonLabel={codeExpired ? '재발송' : '인증번호 받기'}
              onClick={handleSendCode}
              loading={sendingCode}
              disabled={emailLocked || (codeExpiresAt !== null && !codeExpired)}
              input={
                <input
                  id="email"
                  autoComplete="email"
                  readOnly={emailLocked}   // 인증이 끝나면 잠근다. (disabled로 하면 값이 폼에서 빠진다.)
                  className={`${inputClass('email')} join-item ${emailLocked ? 'bg-base-200' : ''}`}
                  {...register('email', {
                    required: '이메일을 입력해 주세요.',
                    pattern: { value: EMAIL_PATTERN, message: '이메일 형식이 올바르지 않습니다.' },
                    maxLength: { value: 100, message: '이메일은 100자 이하여야 합니다.' },
                  })}
                />
              }
            />
          </FormField>

          {codeExpiresAt !== null && !emailLocked && (
            <FormField
              label="인증번호"
              htmlFor="code"
              error={errors.code?.message ?? (codeExpired ? '인증번호 유효 시간이 지났습니다. 재발송해 주세요.' : undefined)}
              hint={codeExpired ? undefined : `남은 시간 ${formatRemaining(remainingSeconds)}`}
            >
              <InputWithButton
                buttonLabel="확인"
                onClick={handleVerifyCode}
                loading={verifyingCode}
                disabled={codeExpired}
                input={
                  <input
                    id="code"
                    maxLength={6}
                    inputMode="numeric"
                    disabled={codeExpired}
                    className={`${inputClass('code')} join-item`}
                    {...register('code', {
                      required: '인증번호를 입력해 주세요.',
                      pattern: { value: /^\d{6}$/, message: '6자리 숫자를 입력해 주세요.' },
                    })}
                  />
                }
              />
            </FormField>
          )}

          {emailLocked && (
            <div role="alert" className="alert alert-success alert-soft mb-4">
              <span>이메일 인증이 완료되었습니다.</span>
            </div>
          )}

          <div className="divider" />

          <fieldset className="mb-6">
            <legend className="mb-2 text-sm font-medium">약관 동의</legend>
            <label className="flex cursor-pointer items-center gap-2">
              <input
                type="checkbox"
                className="checkbox checkbox-sm checkbox-primary"
                checked={allAgreed}
                onChange={(e) => toggleAll(e.target.checked)}
              />
              전체 동의
            </label>
            <div className="divider my-2" />
            {agreements.map((a) => (
              <div key={a.agreementId} className="mb-2 flex items-center justify-between">
                <label className="flex cursor-pointer items-center gap-2">
                  <input
                    type="checkbox"
                    className="checkbox checkbox-sm checkbox-primary"
                    checked={Boolean(agreed[a.agreementId])}
                    onChange={(e) => setAgreed((prev) => ({ ...prev, [a.agreementId]: e.target.checked }))}
                  />
                  ({a.isRequired ? '필수' : '선택'}) {a.title}
                </label>
                <button type="button" className="btn btn-link btn-xs" onClick={() => setViewing(a)}>
                  보기
                </button>
              </div>
            ))}
          </fieldset>

          <button type="submit" className="btn btn-primary w-full" disabled={!allRequiredAgreed || isSubmitting}>
            {isSubmitting && <span className="loading loading-spinner loading-sm" />}
            가입하기
          </button>
        </form>

        <p className="mt-4 text-center text-sm">
          이미 회원이신가요?{' '}
          <Link to="/login" className="link link-primary">
            로그인
          </Link>
        </p>
      </div>

      <Modal open={viewing !== null} title={viewing?.title} onClose={() => setViewing(null)}>
        <div className="pre-wrap text-sm">{viewing?.content}</div>
      </Modal>
    </div>
  );
}

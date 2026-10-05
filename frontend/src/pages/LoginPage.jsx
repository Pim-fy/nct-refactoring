import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { getErrorMessage } from '../api/apiResponse';
import FormField from '../components/FormField';
import { useAuth } from '../hooks/useAuth';
import { useToast } from '../hooks/useToast';

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
  const toast = useToast();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [initialLoginId] = useState(readSavedLoginId);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },   // isSubmitting은 제출이 끝날 때까지 true라 버튼을 잠가 중복 전송을 줄인다.
  } = useForm({
    defaultValues: { loginId: initialLoginId, password: '', keepLogin: false, saveLoginId: Boolean(initialLoginId) },
  });

  const redirectTo = safeRedirect(searchParams.get('redirect'));

  // 이미 로그인한 상태로 들어오면 원래 가려던 곳으로 보낸다.
  if (!loading && user) {
    return <Navigate to={redirectTo} replace />;
  }

  async function onSubmit(values) {
    try {
      await login({ loginId: values.loginId, password: values.password, isKeepLogin: values.keepLogin });
      writeSavedLoginId(values.saveLoginId ? values.loginId : '');
      navigate(redirectTo, { replace: true });
    } catch (error) {
      toast.error(getErrorMessage(error));
    }
  }

  return (
    <div className="card mx-auto mt-12 max-w-sm bg-base-100 shadow-sm">
      <div className="card-body">
        <h1 className="mb-2 text-center text-2xl font-bold">로그인</h1>

        <form onSubmit={handleSubmit(onSubmit)} noValidate>
          <FormField label="아이디" htmlFor="loginId" error={errors.loginId?.message}>
            <input
              id="loginId"
              autoComplete="username"
              className={`input w-full ${errors.loginId ? 'input-error' : ''}`}
              {...register('loginId', { required: '아이디를 입력해 주세요.' })}
            />
          </FormField>

          <FormField label="비밀번호" htmlFor="password" error={errors.password?.message}>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              className={`input w-full ${errors.password ? 'input-error' : ''}`}
              {...register('password', { required: '비밀번호를 입력해 주세요.' })}
            />
          </FormField>

          <div className="mb-4 flex gap-4 text-sm">
            <label className="flex cursor-pointer items-center gap-2">
              <input type="checkbox" className="checkbox checkbox-sm checkbox-primary" {...register('keepLogin')} />
              로그인 유지
            </label>
            <label className="flex cursor-pointer items-center gap-2">
              <input type="checkbox" className="checkbox checkbox-sm checkbox-primary" {...register('saveLoginId')} />
              아이디 저장
            </label>
          </div>

          <button type="submit" className="btn btn-primary w-full" disabled={isSubmitting}>
            {isSubmitting && <span className="loading loading-spinner loading-sm" />}
            로그인
          </button>
        </form>

        <p className="mt-4 text-center text-sm">
          아직 회원이 아니신가요?{' '}
          <Link to="/signup" className="link link-primary">
            회원가입
          </Link>
        </p>
      </div>
    </div>
  );
}

import { createContext } from 'react';

// 값의 형태: { success(text), error(text), warning(text), info(text) }
// 처리 결과와 오류를 화면 위쪽에 잠깐 보여주는 공통 알림이다. (화면 설계서 1장)
export const ToastContext = createContext(null);

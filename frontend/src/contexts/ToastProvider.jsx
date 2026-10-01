import { useCallback, useMemo, useRef, useState } from 'react';
import { ToastContext } from './toastContext';

const VISIBLE_MILLIS = 3000;

// Tailwind는 소스에 글자 그대로 적힌 클래스만 CSS로 만든다. 그래서 `alert-${type}`처럼 조합하지 않고 전체 이름을 적는다.
const ALERT_CLASS = {
  success: 'alert-success',
  error: 'alert-error',
  warning: 'alert-warning',
  info: 'alert-info',
};

export default function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const nextId = useRef(0);

  const show = useCallback((type, text) => {
    const id = nextId.current++;
    setToasts((prev) => [...prev, { id, type, text }]);
    setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), VISIBLE_MILLIS);
  }, []);

  const value = useMemo(
    () => ({
      success: (text) => show('success', text),
      error: (text) => show('error', text),
      warning: (text) => show('warning', text),
      info: (text) => show('info', text),
    }),
    [show]
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="toast toast-top toast-center z-50" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} role="alert" className={`alert ${ALERT_CLASS[t.type]} shadow-md`}>
            <span>{t.text}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

import { useContext } from 'react';
import { ToastContext } from '../contexts/toastContext';

export function useToast() {
  const value = useContext(ToastContext);
  if (!value) {
    throw new Error('useToast는 ToastProvider 안에서만 쓸 수 있습니다.');
  }
  return value;
}

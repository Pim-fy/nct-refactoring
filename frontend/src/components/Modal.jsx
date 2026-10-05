import { useEffect, useRef } from 'react';

// 브라우저 기본 <dialog>를 쓴 모달. 열면 뒤 화면이 막히고, ESC 키와 바깥 클릭으로 닫힌다.
// open 상태는 부모가 정하고, 닫힐 때마다 onClose를 불러 부모의 상태를 맞춘다.
export default function Modal({ open, title, onClose, children }) {
  const dialogRef = useRef(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog ref={dialogRef} className="modal" onClose={onClose}>
      <div className="modal-box">
        <h3 className="mb-4 text-lg font-bold">{title}</h3>
        <div className="max-h-[60vh] overflow-y-auto">{children}</div>
        <div className="modal-action">
          <form method="dialog">
            <button className="btn">닫기</button>
          </form>
        </div>
      </div>
      <form method="dialog" className="modal-backdrop">
        <button aria-label="닫기">close</button>
      </form>
    </dialog>
  );
}

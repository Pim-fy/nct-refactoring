// 라벨, 입력란, 안내·오류 문구를 한 묶음으로 보여준다.
// error가 있으면 빨간 문구, success가 있으면 초록 문구, 둘 다 없으면 hint를 보여준다.
export default function FormField({ label, htmlFor, error, success, hint, children }) {
  return (
    <div className="mb-4">
      {label && (
        <label htmlFor={htmlFor} className="mb-1 block text-sm font-medium">
          {label}
        </label>
      )}
      {children}
      {error ? (
        <p role="alert" className="mt-1 text-sm text-error">
          {error}
        </p>
      ) : success ? (
        <p className="mt-1 text-sm text-success">{success}</p>
      ) : hint ? (
        <p className="mt-1 text-sm text-base-content/60">{hint}</p>
      ) : null}
    </div>
  );
}

import { useEffect, useRef, useState } from 'react';
import { getErrorMessage } from '../api/apiResponse';
import { deleteImage, uploadImage } from '../api/imageApi';
import { useToast } from '../hooks/useToast';

const MAX_BYTES = 5 * 1024 * 1024;
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

// 상품 이미지를 올리고 순서를 정하는 입력. (기능 명세서 3-3, 화면 설계서 경매 등록)
// - 한 장씩 서버에 올리고(상품 용도), 올린 이미지의 식별자를 순서대로 부모에게 돌려준다. 첫 장이 대표 이미지다.
// - 올리는 동안 브라우저에서 만든 미리보기를 보여 준다. (아직 경매에 쓰지 않은 이미지는 올린 본인만 서버에서 볼 수 있다.)
// - 위·아래 버튼으로 순서를 바꾸고, 지우면 서버의 이미지도 지운다.
// value: [{ imageId, previewUrl, name }], onChange(nextValue)
export default function ImageUploader({ value, onChange, maxCount = 10, disabled = false }) {
  const toast = useToast();
  const inputRef = useRef(null);
  const [uploading, setUploading] = useState(false);
  const mountedRef = useRef(true);

  // 올리는 도중에 화면을 떠나면, 끝난 이미지는 목록에 더할 곳이 없으므로 서버에서 지워 "쓰지 않은 이미지 10장" 한도를 차지하지 않게 한다.
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const remaining = maxCount - value.length;

  function validate(file) {
    if (!ALLOWED_TYPES.includes(file.type)) {
      return `${file.name}: JPEG, PNG, WebP 이미지만 올릴 수 있습니다.`;
    }
    if (file.size > MAX_BYTES) {
      return `${file.name}: 이미지는 5MB 이하만 올릴 수 있습니다.`;
    }
    return null;
  }

  async function handleSelect(event) {
    const files = Array.from(event.target.files ?? []);
    event.target.value = '';   // 같은 파일을 다시 고를 수 있게 비운다.
    if (files.length === 0) {
      return;
    }
    if (files.length > remaining) {
      toast.error(`이미지는 최대 ${maxCount}장까지 올릴 수 있습니다. 앞의 ${Math.max(remaining, 0)}장만 올립니다.`);
    }

    setUploading(true);
    // 여러 장은 요청을 한 장씩 보낸다. (API 명세서 2장) 끝난 것부터 목록에 더한다.
    let current = value;
    for (const file of files.slice(0, Math.max(remaining, 0))) {
      const problem = validate(file);
      if (problem) {
        toast.error(problem);
        continue;
      }
      try {
        const uploaded = await uploadImage(file, 'PRODUCT');
        if (!mountedRef.current) {
          deleteImage(uploaded.imageId).catch(() => {});   // 이미 떠난 화면이므로 실패해도 알릴 곳이 없다.
          return;
        }
        current = [...current, { imageId: uploaded.imageId, previewUrl: URL.createObjectURL(file), name: file.name }];
        onChange(current);
      } catch (error) {
        toast.error(`${file.name}: ${getErrorMessage(error)}`);
      }
    }
    if (mountedRef.current) {
      setUploading(false);
    }
  }

  function move(index, delta) {
    const target = index + delta;
    if (target < 0 || target >= value.length) {
      return;
    }
    const next = [...value];
    [next[index], next[target]] = [next[target], next[index]];
    onChange(next);
  }

  async function remove(index) {
    const image = value[index];
    try {
      await deleteImage(image.imageId);   // 서버에서도 지워 "쓰지 않은 이미지 10장" 한도를 차지하지 않게 한다.
    } catch (error) {
      toast.error(getErrorMessage(error));
      return;
    }
    URL.revokeObjectURL(image.previewUrl);
    onChange(value.filter((_, i) => i !== index));
  }

  return (
    <div>
      <div className="mb-2 flex items-center gap-3">
        <button
          type="button"
          className="btn btn-sm"
          disabled={disabled || uploading || remaining <= 0}
          onClick={() => inputRef.current?.click()}
        >
          {uploading ? '올리는 중...' : '이미지 추가'}
        </button>
        <span className="text-sm text-base-content/60">
          {value.length}/{maxCount}장 · JPEG, PNG, WebP · 한 장당 5MB 이하 · 첫 장이 대표 이미지입니다
        </span>
      </div>
      <input
        ref={inputRef}
        type="file"
        accept={ALLOWED_TYPES.join(',')}
        multiple
        className="hidden"
        data-testid="image-input"
        onChange={handleSelect}
      />

      {value.length > 0 && (
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-5">
          {value.map((image, index) => (
            <li key={image.imageId} className="rounded-box border border-base-300 bg-base-100 p-2">
              <div className="relative">
                <img src={image.previewUrl} alt={image.name} className="aspect-square w-full rounded object-cover" />
                {index === 0 && <span className="badge badge-primary absolute left-1 top-1">대표</span>}
              </div>
              <div className="mt-2 flex items-center justify-between gap-1">
                <div className="flex gap-1">
                  <button
                    type="button"
                    className="btn btn-xs"
                    aria-label={`${image.name} 위로`}
                    disabled={disabled || index === 0}
                    onClick={() => move(index, -1)}
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    className="btn btn-xs"
                    aria-label={`${image.name} 아래로`}
                    disabled={disabled || index === value.length - 1}
                    onClick={() => move(index, 1)}
                  >
                    ↓
                  </button>
                </div>
                <button
                  type="button"
                  className="btn btn-xs btn-error btn-outline"
                  aria-label={`${image.name} 삭제`}
                  disabled={disabled}
                  onClick={() => remove(index)}
                >
                  삭제
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

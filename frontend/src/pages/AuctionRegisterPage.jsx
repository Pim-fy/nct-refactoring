import { useEffect, useRef, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { getErrorCode, getErrorMessage, getFieldErrors } from '../api/apiResponse';
import { registerAuction } from '../api/auctionApi';
import { fetchCategories } from '../api/categoryApi';
import { deleteImage } from '../api/imageApi';
import FormField from '../components/FormField';
import ImageUploader from '../components/ImageUploader';
import Modal from '../components/Modal';
import { useToast } from '../hooks/useToast';
import { formatWon } from '../utils/format';

// 서버의 필드 이름을 이 화면의 입력란 이름으로 바꾼다. (AUCTION_INPUT_INVALID의 details)
const SERVER_FIELD_TO_FORM = {
  'product.name': 'name',
  'product.categoryId': 'categoryId',
  'product.description': 'description',
  'product.conditionNote': 'conditionNote',
  'auction.startPrice': 'startPrice',
  'auction.bidUnit': 'bidUnit',
  'auction.endAt': 'endAt',
};

const MIN_END_MINUTES = 10;
const MAX_END_DAYS = 30;

// 입력은 사용자의 현재 시간대의 날짜·시각이다. 이 서비스는 한국 시간을 기준으로 하므로 +09:00을 붙여 보낸다.
function toEndAtIso(value) {
  const withSeconds = value.length === 16 ? `${value}:00` : value;
  return `${withSeconds}+09:00`;
}

// 종료 시각 검증. 렌더링 중이 아니라 입력을 확인할 때만 호출되도록 컴포넌트 밖에 둔다. (지금 시각을 쓰기 때문이다.)
function validateEndAt(value) {
  const time = new Date(value).getTime();
  if (Number.isNaN(time)) return '종료 시각을 입력해 주세요.';
  const now = Date.now();
  if (time < now + MIN_END_MINUTES * 60 * 1000) return '종료 시각은 지금부터 10분 뒤 이후여야 합니다.';
  if (time > now + MAX_END_DAYS * 24 * 60 * 60 * 1000) return '종료 시각은 30일 이내여야 합니다.';
  return true;
}

// 경매 등록 (기능 명세서 3-4, 화면 설계서 경매 등록)
// 입력 → 확인 모달(등록 후에는 수정·삭제할 수 없음을 안내) → 등록 → 내 경매 목록으로 이동
export default function AuctionRegisterPage() {
  const toast = useToast();
  const navigate = useNavigate();

  const [categories, setCategories] = useState([]);
  const [images, setImages] = useState([]);
  const [imageError, setImageError] = useState('');
  const [confirmValues, setConfirmValues] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  // 화면을 벗어날 때 등록하지 않은 이미지를 서버에서 지운다. (쓰지 않은 이미지는 한 회원이 10장까지만 올릴 수 있어서,
  // 지우지 않으면 올리고 나간 이미지가 한도를 차지한다.) 브라우저를 닫는 경우까지는 지우지 못한다.
  const imagesRef = useRef(images);
  const submittedRef = useRef(false);
  useEffect(() => {
    imagesRef.current = images;
  }, [images]);
  useEffect(
    () => () => {
      if (submittedRef.current) {
        return;
      }
      imagesRef.current.forEach((image) => {
        URL.revokeObjectURL(image.previewUrl);
        deleteImage(image.imageId).catch(() => {});
      });
    },
    [],
  );

  // 사용 중인 카테고리 목록. 요청 결과가 올 때만 상태를 바꾼다.
  useEffect(() => {
    let cancelled = false;
    fetchCategories()
      .then((data) => {
        if (!cancelled) setCategories(data.categories);
      })
      .catch((error) => {
        if (!cancelled) toast.error(getErrorMessage(error));
      });
    return () => {
      cancelled = true;
    };
  }, [toast]);

  const {
    register,
    handleSubmit,
    control,
    setError,
    formState: { errors },
  } = useForm({
    mode: 'onChange',   // 시작가와 입찰 단위의 배수 관계는 입력하는 즉시 안내한다. (화면 설계서)
    defaultValues: { name: '', categoryId: '', description: '', conditionNote: '', startPrice: '', bidUnit: '', endAt: '' },
  });
  const descriptionLength = useWatch({ control, name: 'description' })?.length ?? 0;

  const priceRules = (label, min, max) => ({
    required: `${label}을 입력해 주세요.`,
    validate: {
      integer: (v) => Number.isInteger(Number(v)) || '정수로 입력해 주세요.',
      min: (v) => Number(v) >= min || `${label}은 ${min.toLocaleString('ko-KR')}원 이상이어야 합니다.`,
      max: (v) => max === null || Number(v) <= max || `${label}은 ${max.toLocaleString('ko-KR')}원 이하여야 합니다.`,
      step: (v) => Number(v) % 100 === 0 || `${label}은 100원 단위여야 합니다.`,
    },
  });

  function onValid(values) {
    if (images.length === 0) {
      setImageError('상품 이미지를 한 장 이상 올려 주세요.');
      return;
    }
    setImageError('');
    setConfirmValues(values);
  }

  function applyServerError(error) {
    const code = getErrorCode(error);
    if (code === 'AUCTION_INPUT_INVALID') {
      let handled = false;
      for (const detail of getFieldErrors(error)) {
        const formField = SERVER_FIELD_TO_FORM[detail.field];
        if (formField) {
          setError(formField, { type: 'server', message: detail.message });
          handled = true;
        } else if (detail.field === 'product.imageIds') {
          setImageError(detail.message);
          handled = true;
        }
      }
      if (!handled) toast.error(getErrorMessage(error));
      return;
    }
    if (code === 'AUCTION_END_TIME_INVALID') {
      setError('endAt', { type: 'server', message: getErrorMessage(error) });
      return;
    }
    if (code === 'AUCTION_IMAGE_REQUIRED') {
      setImageError(getErrorMessage(error));
      return;
    }
    toast.error(getErrorMessage(error));   // 거래 제한 회원이면 서버의 안내 문구를 그대로 보여 준다. (기능 명세서 2-2)
  }

  async function confirmRegister() {
    setSubmitting(true);
    try {
      await registerAuction({
        product: {
          name: confirmValues.name,
          categoryId: Number(confirmValues.categoryId),
          description: confirmValues.description,
          conditionNote: confirmValues.conditionNote,
          imageIds: images.map((image) => image.imageId),
        },
        auction: {
          startPrice: Number(confirmValues.startPrice),
          bidUnit: Number(confirmValues.bidUnit),
          endAt: toEndAtIso(confirmValues.endAt),
        },
      });
      submittedRef.current = true;
      toast.success('경매를 등록했습니다.');
      navigate('/my/auctions');
    } catch (error) {
      setConfirmValues(null);
      applyServerError(error);
    } finally {
      setSubmitting(false);
    }
  }

  const categoryName = categories.find((category) => String(category.categoryId) === String(confirmValues?.categoryId))?.name;

  return (
    <div className="mx-auto max-w-3xl rounded-box bg-base-100 p-6 shadow-sm">
      <h1 className="mb-1 text-xl font-bold">경매 등록</h1>
      <p className="mb-6 text-sm text-base-content/60">
        등록 후에는 수정·삭제할 수 없습니다. 거래는 택배이며 배송비는 판매자가 부담합니다.
      </p>

      <form onSubmit={handleSubmit(onValid)} noValidate>
        <h2 className="mb-3 font-semibold">상품 정보</h2>
        <FormField label="상품명" htmlFor="name" error={errors.name?.message}>
          <input
            id="name"
            className="input input-bordered w-full"
            maxLength={100}
            {...register('name', {
              required: '상품명을 입력해 주세요.',
              validate: (v) => v.trim().length > 0 || '상품명을 입력해 주세요.',
              maxLength: { value: 100, message: '상품명은 100자 이하여야 합니다.' },
            })}
          />
        </FormField>
        <FormField label="카테고리" htmlFor="categoryId" error={errors.categoryId?.message}>
          <select
            id="categoryId"
            className="select select-bordered w-full"
            {...register('categoryId', { required: '카테고리를 선택해 주세요.' })}
          >
            <option value="">선택해 주세요</option>
            {categories.map((category) => (
              <option key={category.categoryId} value={category.categoryId}>
                {category.name}
              </option>
            ))}
          </select>
        </FormField>
        <FormField
          label="상품 설명"
          htmlFor="description"
          error={errors.description?.message}
          hint={`${descriptionLength.toLocaleString('ko-KR')} / 5,000자`}
        >
          <textarea
            id="description"
            className="textarea textarea-bordered h-32 w-full"
            maxLength={5000}
            {...register('description', {
              required: '상품 설명을 입력해 주세요.',
              validate: (v) => v.trim().length > 0 || '상품 설명을 입력해 주세요.',
              maxLength: { value: 5000, message: '상품 설명은 5,000자 이하여야 합니다.' },
            })}
          />
        </FormField>
        <FormField label="상태 설명" htmlFor="conditionNote" error={errors.conditionNote?.message} hint="사용감, 흠집 등 물품의 상태를 적어 주세요.">
          <input
            id="conditionNote"
            className="input input-bordered w-full"
            maxLength={500}
            {...register('conditionNote', {
              required: '상태 설명을 입력해 주세요.',
              validate: (v) => v.trim().length > 0 || '상태 설명을 입력해 주세요.',
              maxLength: { value: 500, message: '상태 설명은 500자 이하여야 합니다.' },
            })}
          />
        </FormField>

        <h2 className="mb-3 mt-6 font-semibold">이미지</h2>
        <div className="mb-4">
          <ImageUploader value={images} onChange={setImages} disabled={submitting} />
          {imageError && (
            <p role="alert" className="mt-2 text-sm text-error">
              {imageError}
            </p>
          )}
        </div>

        <h2 className="mb-3 mt-6 font-semibold">거래 조건</h2>
        <div className="grid gap-x-4 sm:grid-cols-2">
          <FormField
            label="시작가(원)"
            htmlFor="startPrice"
            error={errors.startPrice?.message}
            hint="100원 이상 5,000만원 이하, 100원 단위"
          >
            <input
              id="startPrice"
              type="number"
              inputMode="numeric"
              className="input input-bordered w-full"
              {...register('startPrice', {
                ...priceRules('시작가', 100, 50000000),
                validate: {
                  ...priceRules('시작가', 100, 50000000).validate,
                  multiple: (v, formValues) => {
                    const unit = Number(formValues.bidUnit);
                    if (!unit || unit < 100 || unit % 100 !== 0) return true;   // 입찰 단위가 올바르지 않으면 그쪽에서 안내한다.
                    return Number(v) % unit === 0 || '시작가는 입찰 단위의 배수여야 합니다.';
                  },
                },
              })}
            />
          </FormField>
          <FormField
            label="입찰 단위(원)"
            htmlFor="bidUnit"
            error={errors.bidUnit?.message}
            hint="100원 이상, 100원 단위. 시작가는 입찰 단위의 배수여야 합니다."
          >
            <input
              id="bidUnit"
              type="number"
              inputMode="numeric"
              className="input input-bordered w-full"
              {...register('bidUnit', { ...priceRules('입찰 단위', 100, null), deps: ['startPrice'] })}
            />
          </FormField>
        </div>
        <FormField
          label="종료 시각"
          htmlFor="endAt"
          error={errors.endAt?.message}
          hint="지금부터 10분 뒤 이후, 30일 이내(한국 시간)"
        >
          <input
            id="endAt"
            type="datetime-local"
            className="input input-bordered w-full sm:w-72"
            {...register('endAt', {
              required: '종료 시각을 입력해 주세요.',
              validate: validateEndAt,
            })}
          />
        </FormField>

        <div className="mt-6 flex justify-end">
          <button type="submit" className="btn btn-primary" disabled={submitting}>
            등록하기
          </button>
        </div>
      </form>

      <Modal open={confirmValues !== null} title="이 내용으로 경매를 등록할까요?" onClose={() => setConfirmValues(null)}>
        {confirmValues && (
          <>
            <dl className="mb-4 grid grid-cols-[6rem_1fr] gap-y-2 text-sm">
              <dt className="text-base-content/60">상품명</dt>
              <dd>{confirmValues.name}</dd>
              <dt className="text-base-content/60">카테고리</dt>
              <dd>{categoryName}</dd>
              <dt className="text-base-content/60">이미지</dt>
              <dd>{images.length}장</dd>
              <dt className="text-base-content/60">시작가</dt>
              <dd>{formatWon(Number(confirmValues.startPrice))}</dd>
              <dt className="text-base-content/60">입찰 단위</dt>
              <dd>{formatWon(Number(confirmValues.bidUnit))}</dd>
              <dt className="text-base-content/60">종료 시각</dt>
              <dd>{confirmValues.endAt.replace('T', ' ')}</dd>
            </dl>
            <p className="mb-4 rounded bg-warning/20 p-3 text-sm">
              등록한 뒤에는 거래 조건을 수정하거나 경매를 삭제할 수 없습니다. 내용을 다시 확인해 주세요.
            </p>
            <button type="button" className="btn btn-primary" disabled={submitting} onClick={confirmRegister}>
              {submitting ? '등록 중...' : '등록하기'}
            </button>
          </>
        )}
      </Modal>
    </div>
  );
}

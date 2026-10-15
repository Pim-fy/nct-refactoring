import { useForm } from 'react-hook-form';
import { getErrorMessage, getFieldErrors } from '../api/apiResponse';
import { saveDelivery } from '../api/tradeApi';
import { useToast } from '../hooks/useToast';
import FormField from './FormField';

// 배송지 입력·수정 (낙찰자, 화면 설계서 3-5, 기능 명세서 3-8). 발송이 등록되기 전(준비 상태)까지 다시 입력해 고칠 수 있다.
// delivery가 있으면 그 값으로 채운다. 서버가 필드별로 알려 주는 입력 오류는 해당 입력란 아래에 보여 준다.
export default function DeliveryForm({ tradeId, delivery, onSaved }) {
  const toast = useToast();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({
    mode: 'onChange',
    defaultValues: {
      receiverName: delivery?.receiverName ?? '',
      receiverPhone: delivery?.receiverPhone ?? '',
      zipCode: delivery?.zipCode ?? '',
      address: delivery?.address ?? '',
      addressDetail: delivery?.addressDetail ?? '',
      deliveryMessage: delivery?.deliveryMessage ?? '',
    },
  });

  async function onSubmit(values) {
    try {
      await saveDelivery(tradeId, values);
      toast.success('배송지를 저장했습니다.');
      onSaved();
    } catch (error) {
      const details = getFieldErrors(error);
      let handled = false;
      for (const detail of details) {
        if (detail.field in values) {
          setError(detail.field, { type: 'server', message: detail.message });
          handled = true;
        }
      }
      if (!handled) {
        toast.error(getErrorMessage(error));
        onSaved();   // 거래 상태가 바뀌었을 수 있으므로 다시 받는다.
      }
    }
  }

  return (
    <form className="mt-4 rounded-box border border-base-300 p-4" onSubmit={handleSubmit(onSubmit)} noValidate>
      <h2 className="mb-3 font-bold">{delivery ? '배송지 수정' : '배송지 입력'}</h2>
      <FormField label="수령인" htmlFor="receiverName" error={errors.receiverName?.message}>
        <input
          id="receiverName"
          className="input input-bordered w-full"
          maxLength={50}
          {...register('receiverName', { required: '수령인을 입력해 주세요.', validate: (v) => v.trim() !== '' || '수령인을 입력해 주세요.' })}
        />
      </FormField>
      <FormField label="연락처" htmlFor="receiverPhone" error={errors.receiverPhone?.message} hint="숫자와 하이픈으로 입력해 주세요. (예: 010-1234-5678)">
        <input
          id="receiverPhone"
          inputMode="tel"
          className="input input-bordered w-full"
          maxLength={20}
          {...register('receiverPhone', {
            required: '연락처를 입력해 주세요.',
            pattern: { value: /^(?=.{9,20}$)[0-9]+(-[0-9]+)*$/, message: '연락처는 숫자와 하이픈으로 입력해 주세요.' },
          })}
        />
      </FormField>
      <FormField label="우편번호" htmlFor="zipCode" error={errors.zipCode?.message}>
        <input
          id="zipCode"
          inputMode="numeric"
          className="input input-bordered w-40"
          maxLength={5}
          {...register('zipCode', { required: '우편번호를 입력해 주세요.', pattern: { value: /^[0-9]{5}$/, message: '우편번호는 5자리 숫자로 입력해 주세요.' } })}
        />
      </FormField>
      <FormField label="주소" htmlFor="address" error={errors.address?.message}>
        <input
          id="address"
          className="input input-bordered w-full"
          maxLength={255}
          {...register('address', { required: '주소를 입력해 주세요.', validate: (v) => v.trim() !== '' || '주소를 입력해 주세요.' })}
        />
      </FormField>
      <FormField label="상세 주소 (선택)" htmlFor="addressDetail" error={errors.addressDetail?.message}>
        <input id="addressDetail" className="input input-bordered w-full" maxLength={255} {...register('addressDetail')} />
      </FormField>
      <FormField label="배송 요청 사항 (선택)" htmlFor="deliveryMessage" error={errors.deliveryMessage?.message}>
        <input id="deliveryMessage" className="input input-bordered w-full" maxLength={255} {...register('deliveryMessage')} />
      </FormField>
      <p className="mb-3 text-sm text-base-content/60">판매자가 발송을 등록하기 전까지 다시 입력해 고칠 수 있습니다.</p>
      <button type="submit" className="btn btn-primary w-full" disabled={isSubmitting}>
        {isSubmitting ? '저장 중...' : '배송지 저장'}
      </button>
    </form>
  );
}

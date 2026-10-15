import { useForm } from 'react-hook-form';
import { getErrorMessage, getFieldErrors } from '../api/apiResponse';
import { fetchTrade, registerShipment } from '../api/tradeApi';
import { useToast } from '../hooks/useToast';
import FormField from './FormField';

// 발송 등록 (판매자, 화면 설계서 3-5, 기능 명세서 3-8). 배송지가 입력된 준비 상태에서만 보인다.
// 등록하면 거래가 진행 상태가 되고 구매 확정 기한(발송 후 10일)이 정해진다.
const DELIVERY_FIELDS = ['receiverName', 'receiverPhone', 'zipCode', 'address', 'addressDetail', 'deliveryMessage'];

// 두 배송지의 입력 항목이 같은지. 운송장 관련 항목은 비교하지 않는다.
function sameDelivery(a, b) {
  if (!a || !b) {
    return false;
  }
  return DELIVERY_FIELDS.every((field) => (a[field] ?? null) === (b[field] ?? null));
}

export default function ShipmentForm({ tradeId, delivery, onRegistered }) {
  const toast = useToast();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ mode: 'onChange', defaultValues: { courierName: '', trackingNumber: '' } });

  async function onSubmit(values) {
    try {
      // 판매자가 화면에서 본 배송지와 지금 서버의 배송지가 다르면(낙찰자가 그 사이에 고쳤다면) 등록하지 않고 최신 화면으로 바꾼다.
      const latest = await fetchTrade(tradeId);
      if (!sameDelivery(delivery, latest.delivery) || !latest.availableActions.includes('REGISTER_SHIPMENT')) {
        toast.error('낙찰자가 배송지를 수정했습니다. 최신 배송지를 확인한 뒤 다시 등록해 주세요.');
        onRegistered();
        return;
      }
      await registerShipment(tradeId, values);
      toast.success('발송을 등록했습니다.');
      onRegistered();
    } catch (error) {
      let handled = false;
      for (const detail of getFieldErrors(error)) {
        if (detail.field in values) {
          setError(detail.field, { type: 'server', message: detail.message });
          handled = true;
        }
      }
      if (!handled) {
        toast.error(getErrorMessage(error));
        onRegistered();
      }
    }
  }

  return (
    <form className="mt-4 rounded-box border border-base-300 p-4" onSubmit={handleSubmit(onSubmit)} noValidate>
      <h2 className="mb-3 font-bold">발송 등록</h2>
      <FormField label="택배사" htmlFor="courierName" error={errors.courierName?.message}>
        <input
          id="courierName"
          className="input input-bordered w-full"
          maxLength={50}
          {...register('courierName', { required: '택배사를 입력해 주세요.', validate: (v) => v.trim() !== '' || '택배사를 입력해 주세요.' })}
        />
      </FormField>
      <FormField label="운송장 번호" htmlFor="trackingNumber" error={errors.trackingNumber?.message} hint="영문, 숫자, 하이픈으로 입력해 주세요.">
        <input
          id="trackingNumber"
          className="input input-bordered w-full"
          maxLength={50}
          {...register('trackingNumber', {
            required: '운송장 번호를 입력해 주세요.',
            pattern: { value: /^[0-9A-Za-z-]+$/, message: '운송장 번호는 영문, 숫자, 하이픈으로 입력해 주세요.' },
          })}
        />
      </FormField>
      <p className="mb-3 text-sm text-base-content/60">발송을 등록하면 낙찰자가 구매를 확정할 수 있고, 10일 안에 확정이 없으면 자동으로 확정됩니다.</p>
      <button type="submit" className="btn btn-primary w-full" disabled={isSubmitting}>
        {isSubmitting ? '등록 중...' : '발송 등록'}
      </button>
    </form>
  );
}

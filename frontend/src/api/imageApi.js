import api from './axios';
import { unwrap } from './apiResponse';

const BASE_URL = import.meta.env.VITE_API_BASE_URL;

// 이미지 한 장을 올린다. usageType은 'PRODUCT'(상품) 또는 'PROFILE'(프로필)이다. (API 명세서 2장)
// 응답: { imageId, url, originalName, contentType, fileSize }
// FormData를 보내면 axios가 브라우저의 멀티파트 형식으로 요청하므로 Content-Type을 직접 지정하지 않는다.
// 서버 규칙: JPEG·PNG·WebP만, 5MB 이하. 올렸지만 아직 경매에 쓰지 않은 상품 이미지는 10장까지,
// 현재 프로필로 쓰지 않는 프로필 이미지는 1장까지다. 넘으면 409 FILE_COUNT_EXCEEDED.
export async function uploadImage(file, usageType) {
  const form = new FormData();
  form.append('file', file);
  form.append('usageType', usageType);
  return unwrap(await api.post('/images', form));
}

// 올린 본인이 지운다. 경매에 쓰인 상품 이미지는 지울 수 없다(409 FILE_IN_USE).
export async function deleteImage(imageId) {
  return unwrap(await api.delete(`/images/${imageId}`));
}

// 서버가 응답에 담아 주는 이미지 경로(예: /api/images/101)를 <img src>에 넣을 전체 주소로 바꾼다.
// 경로가 없으면 null이다. API 주소의 출처(주소와 포트)를 앞에 붙인다.
export function resolveImageUrl(path) {
  if (!path) {
    return null;
  }
  const origin = new URL(BASE_URL, window.location.origin).origin;
  return origin + path;
}

// <img src>에 넣는 주소. 서버가 파일을 읽어 응답하는 API 주소다.
// 올린 본인에게만 보이는(아직 경매에 쓰지 않은) 상품 이미지는 쿠키가 있어야 보이는데, <img>는 다른 출처에서
// 쿠키를 보내지 않으므로 이런 이미지를 보여 줄 때는 <img crossOrigin="use-credentials">를 쓰거나
// 올리기 전 화면에서는 브라우저의 미리보기(URL.createObjectURL)를 쓴다.
export function imageSrc(imageId) {
  return `${BASE_URL}/images/${imageId}`;
}

import api from './axios';
import { unwrap } from './apiResponse';

// 사용 중인 카테고리 목록(공개). 응답: { categories: [{ categoryId, name, sortOrder }] }
export async function fetchCategories() {
  return unwrap(await api.get('/categories'));
}

// 관리자 카테고리 목록. 사용하지 않는 카테고리를 포함한다.
// 응답: { categories: [{ categoryId, name, sortOrder, isActive, productCount }] }
export async function fetchAdminCategories() {
  return unwrap(await api.get('/admin/categories'));
}

// 응답: { categoryId }. 삭제된 카테고리와 같은 이름이면 그 카테고리가 되살아나고 같은 categoryId를 돌려준다.
export async function createCategory(payload) {
  return unwrap(await api.post('/admin/categories', payload));
}

// 보낸 필드만 수정한다. payload: { name?, sortOrder?, isActive? }
export async function updateCategory(categoryId, payload) {
  return unwrap(await api.patch(`/admin/categories/${categoryId}`, payload));
}

export async function deleteCategory(categoryId) {
  return unwrap(await api.delete(`/admin/categories/${categoryId}`));
}

import api from './axios';
import { unwrap } from './apiResponse';

// 관리자 회원 목록(최신 가입순). 관리자 계정과 탈퇴 회원도 나온다. (API 명세서 7장)
// params: { status?, keyword?, page, size } — status는 NORMAL|RESTRICTED_TRADE|RESTRICTED_LOGIN|WITHDRAWN
// 응답: { content: [{ memberId, loginId, nickname, email, memberStatus, memberRole, createdAt,
//                   restriction: { restrictionId, restrictionLevel, reason, isPermanent, startedAt, expiresAt } | null }], page, size, totalElements, totalPages }
export async function fetchAdminMembers(params = {}) {
  return unwrap(await api.get('/admin/members', { params }));
}

// 이용 제한. 이미 제한 중인 회원은 화면에서 확인한 현재 제한의 식별자(expectedRestrictionId)를 보내야 새 제한으로 교체한다.
// 식별자가 없으면 409 MEMBER_ALREADY_RESTRICTED, 그사이 제한이 바뀌었으면 409 MEMBER_RESTRICTION_CHANGED다.
// payload: { restrictionLevel: 'RESTRICTED_TRADE'|'RESTRICTED_LOGIN', isPermanent, expiresAt?(오프셋 포함, 기간 지정일 때만, 지금보다 뒤 10년 안), reason, expectedRestrictionId? }
// 응답: { memberId, memberStatus, restrictionId, startedAt, expiresAt }
export async function restrictMember(memberId, payload) {
  return unwrap(await api.post(`/admin/members/${memberId}/restrictions`, payload));
}

// 이용 제한 해제. payload: { restrictionId(화면에서 확인한 현재 제한), reason }
// 제한 중이 아니면 409 MEMBER_NOT_RESTRICTED, 그사이 제한이 바뀌었으면 409 MEMBER_RESTRICTION_CHANGED다. 응답: { memberId, memberStatus, releasedAt }
export async function releaseMember(memberId, payload) {
  return unwrap(await api.delete(`/admin/members/${memberId}/restrictions`, { data: payload }));
}

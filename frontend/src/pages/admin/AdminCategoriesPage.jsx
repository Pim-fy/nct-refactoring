import { useCallback, useEffect, useState } from 'react';
import { getErrorMessage } from '../../api/apiResponse';
import * as categoryApi from '../../api/categoryApi';
import Modal from '../../components/Modal';
import { useToast } from '../../hooks/useToast';

// 관리자 카테고리 관리 (기능 명세서 3-10)
// - 추가: 이름과 순서(생략하면 맨 뒤). 삭제한 카테고리와 같은 이름이면 그 카테고리가 되살아난다.
// - 수정: 이름·순서, 사용 여부 켜고 끄기
// - 삭제: 쓰는 상품이 있으면 삭제할 수 없고 사용 여부만 끈다.
export default function AdminCategoriesPage() {
  const toast = useToast();
  const [categories, setCategories] = useState([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const [newName, setNewName] = useState('');
  const [newOrder, setNewOrder] = useState('');

  const [editingId, setEditingId] = useState(null);
  const [editName, setEditName] = useState('');
  const [editOrder, setEditOrder] = useState('');

  const [deleteTarget, setDeleteTarget] = useState(null);

  // 추가·수정·삭제한 뒤 목록을 다시 받을 때 쓴다.
  const load = useCallback(async () => {
    try {
      const data = await categoryApi.fetchAdminCategories();
      setCategories(data.categories);
    } catch (error) {
      toast.error(getErrorMessage(error));
    }
  }, [toast]);

  // 화면을 처음 열 때 목록을 받는다. 요청 결과가 올 때만 상태를 바꾼다.
  useEffect(() => {
    let cancelled = false;
    categoryApi
      .fetchAdminCategories()
      .then((data) => {
        if (!cancelled) setCategories(data.categories);
      })
      .catch((error) => {
        if (!cancelled) toast.error(getErrorMessage(error));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [toast]);

  // 요청을 보내는 동안 버튼을 잠그고, 성공하면 목록을 다시 받는다. 성공 여부를 돌려준다.
  async function run(action, successMessage) {
    setBusy(true);
    try {
      await action();
      toast.success(successMessage);
      await load();
      return true;
    } catch (error) {
      toast.error(getErrorMessage(error));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function handleAdd(event) {
    event.preventDefault();
    const name = newName.trim();
    if (!name) {
      toast.error('카테고리 이름을 입력해 주세요.');
      return;
    }
    const payload = newOrder === '' ? { name } : { name, sortOrder: Number(newOrder) };
    const ok = await run(() => categoryApi.createCategory(payload), '카테고리를 추가했습니다.');
    if (ok) {
      setNewName('');
      setNewOrder('');
    }
  }

  function startEdit(category) {
    setEditingId(category.categoryId);
    setEditName(category.name);
    setEditOrder(String(category.sortOrder));
  }

  async function saveEdit(category) {
    const payload = {};
    const name = editName.trim();
    if (!name) {
      toast.error('카테고리 이름을 입력해 주세요.');
      return;
    }
    if (name !== category.name) payload.name = name;
    if (editOrder !== '' && Number(editOrder) !== category.sortOrder) payload.sortOrder = Number(editOrder);
    if (Object.keys(payload).length === 0) {
      setEditingId(null);
      return;
    }
    const ok = await run(() => categoryApi.updateCategory(category.categoryId, payload), '저장했습니다.');
    if (ok) setEditingId(null);
  }

  function toggleActive(category) {
    return run(
      () => categoryApi.updateCategory(category.categoryId, { isActive: !category.isActive }),
      category.isActive ? '사용하지 않도록 바꿨습니다.' : '사용하도록 바꿨습니다.',
    );
  }

  async function confirmDelete() {
    const target = deleteTarget;
    setDeleteTarget(null);
    const ok = await run(() => categoryApi.deleteCategory(target.categoryId), '삭제했습니다.');
    // 수정 중이던 행을 삭제했으면 편집 상태도 풀어, 없는 행의 편집 상태가 남지 않게 한다.
    if (ok && editingId === target.categoryId) setEditingId(null);
  }

  return (
    <div className="rounded-box bg-base-100 p-6 shadow-sm">
      <h1 className="mb-1 text-xl font-bold">카테고리 관리</h1>
      <p className="mb-6 text-sm text-base-content/60">
        사용 중인 카테고리는 삭제할 수 없습니다. 삭제하지 않고 사용 여부만 꺼 주세요. 삭제한 카테고리와 같은 이름으로 추가하면 그 카테고리가 되살아납니다.
      </p>

      <form onSubmit={handleAdd} className="mb-6 flex flex-wrap items-end gap-2">
        <label className="form-control">
          <span className="mb-1 block text-sm font-medium">이름</span>
          <input
            className="input input-bordered w-56"
            value={newName}
            maxLength={50}
            onChange={(event) => setNewName(event.target.value)}
            placeholder="카테고리 이름"
          />
        </label>
        <label className="form-control">
          <span className="mb-1 block text-sm font-medium">순서 (선택)</span>
          <input
            className="input input-bordered w-28"
            type="number"
            min={0}
            max={9999}
            value={newOrder}
            onChange={(event) => setNewOrder(event.target.value)}
            placeholder="맨 뒤"
          />
        </label>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          추가
        </button>
      </form>

      {loading ? (
        <div className="flex justify-center py-10">
          <span className="loading loading-spinner loading-lg text-primary" />
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="table">
            <thead>
              <tr>
                <th className="w-24">순서</th>
                <th>이름</th>
                <th className="w-24">상품 수</th>
                <th className="w-24">사용</th>
                <th className="w-52">작업</th>
              </tr>
            </thead>
            <tbody>
              {categories.length === 0 && (
                <tr>
                  <td colSpan={5} className="py-8 text-center text-base-content/60">
                    카테고리가 없습니다.
                  </td>
                </tr>
              )}
              {categories.map((category) => {
                const editing = editingId === category.categoryId;
                return (
                  <tr key={category.categoryId}>
                    <td>
                      {editing ? (
                        <input
                          className="input input-bordered input-sm w-20"
                          type="number"
                          min={0}
                          max={9999}
                          value={editOrder}
                          onChange={(event) => setEditOrder(event.target.value)}
                        />
                      ) : (
                        category.sortOrder
                      )}
                    </td>
                    <td>
                      {editing ? (
                        <input
                          className="input input-bordered input-sm w-56"
                          value={editName}
                          maxLength={50}
                          onChange={(event) => setEditName(event.target.value)}
                        />
                      ) : (
                        category.name
                      )}
                    </td>
                    <td>{category.productCount}</td>
                    <td>
                      <input
                        type="checkbox"
                        className="toggle toggle-primary toggle-sm"
                        checked={category.isActive}
                        disabled={busy}
                        onChange={() => toggleActive(category)}
                        aria-label={`${category.name} 사용 여부`}
                      />
                    </td>
                    <td>
                      <div className="flex gap-2">
                        {editing ? (
                          <>
                            <button type="button" className="btn btn-sm btn-primary" disabled={busy} onClick={() => saveEdit(category)}>
                              저장
                            </button>
                            <button type="button" className="btn btn-sm" disabled={busy} onClick={() => setEditingId(null)}>
                              취소
                            </button>
                          </>
                        ) : (
                          <>
                            <button type="button" className="btn btn-sm" disabled={busy} onClick={() => startEdit(category)}>
                              수정
                            </button>
                            <button
                              type="button"
                              className="btn btn-sm btn-error btn-outline"
                              disabled={busy || category.productCount > 0}
                              title={category.productCount > 0 ? '사용 중인 카테고리는 삭제할 수 없습니다. 사용 여부만 꺼 주세요.' : undefined}
                              onClick={() => setDeleteTarget(category)}
                            >
                              삭제
                            </button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <Modal open={deleteTarget !== null} title="카테고리 삭제" onClose={() => setDeleteTarget(null)}>
        <p className="mb-4">
          &apos;{deleteTarget?.name}&apos; 카테고리를 삭제할까요? 삭제해도 기록은 남고, 목록과 등록 화면에서만 빠집니다.
        </p>
        <button type="button" className="btn btn-error" disabled={busy} onClick={confirmDelete}>
          삭제
        </button>
      </Modal>
    </div>
  );
}

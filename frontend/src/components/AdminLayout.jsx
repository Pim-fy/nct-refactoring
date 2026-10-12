import { NavLink, Outlet } from 'react-router-dom';

// 관리자 화면의 공통 구조: 좌측 메뉴 + 본문 (화면 설계서 관리자 화면).
// 메뉴는 구현된 기능만 둔다. 신고·분쟁·경매·회원 관리는 해당 기능을 만들 때 추가한다.
const MENUS = [{ to: '/admin/categories', label: '카테고리 관리' }];

export default function AdminLayout() {
  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 md:flex-row">
      <aside className="md:w-52 md:shrink-0">
        <ul className="menu w-full rounded-box bg-base-100 shadow-sm">
          <li className="menu-title">관리자</li>
          {MENUS.map((menu) => (
            <li key={menu.to}>
              <NavLink to={menu.to} className={({ isActive }) => (isActive ? 'menu-active' : '')}>
                {menu.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </aside>
      <section className="min-w-0 flex-1">
        <Outlet />
      </section>
    </div>
  );
}

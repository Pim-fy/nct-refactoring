import { Route, Routes } from 'react-router-dom';
import AdminLayout from './components/AdminLayout';
import Layout from './components/Layout';
import ProtectedRoute from './components/ProtectedRoute';
import AdminCategoriesPage from './pages/admin/AdminCategoriesPage';
import AuctionDetailPage from './pages/AuctionDetailPage';
import AuctionRegisterPage from './pages/AuctionRegisterPage';
import HomePage from './pages/HomePage';
import LoginPage from './pages/LoginPage';
import MyAuctionsPage from './pages/MyAuctionsPage';
import MyBidsPage from './pages/MyBidsPage';
import MyTradesPage from './pages/MyTradesPage';
import NotFoundPage from './pages/NotFoundPage';
import PointPage from './pages/PointPage';
import SignupPage from './pages/SignupPage';
import TradeDetailPage from './pages/TradeDetailPage';

// 화면 경로는 화면 설계서 2장을 따른다.
// 회원 전용 화면은 앞으로 <Route element={<ProtectedRoute />}> 안에, 관리자 화면은 role="ADMIN"을 준 ProtectedRoute 안에 넣는다.
export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<HomePage />} />
        <Route path="/auctions/:id" element={<AuctionDetailPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route element={<ProtectedRoute role="MEMBER" />}>
          <Route path="/auctions/new" element={<AuctionRegisterPage />} />
          <Route path="/my/auctions" element={<MyAuctionsPage />} />
          <Route path="/my/bids" element={<MyBidsPage />} />
          <Route path="/my/trades" element={<MyTradesPage />} />
          <Route path="/my/trades/:id" element={<TradeDetailPage />} />
          <Route path="/my/points" element={<PointPage />} />
        </Route>
        <Route element={<ProtectedRoute role="ADMIN" />}>
          <Route element={<AdminLayout />}>
            <Route path="/admin/categories" element={<AdminCategoriesPage />} />
          </Route>
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

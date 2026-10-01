import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App as AntdApp, ConfigProvider } from 'antd'
import koKR from 'antd/locale/ko_KR'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import App from './App.jsx'
import AuthProvider from './contexts/AuthProvider.jsx'

// AntdApp은 공통 알림(message)을 컴포넌트에서 쓸 수 있게 해 준다.
createRoot(document.getElementById('root')).render(
  <StrictMode>
    <ConfigProvider locale={koKR}>
      <AntdApp>
        <BrowserRouter>
          <AuthProvider>
            <App />
          </AuthProvider>
        </BrowserRouter>
      </AntdApp>
    </ConfigProvider>
  </StrictMode>,
)

import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { lazy, Suspense } from 'react'
import Layout from '@/components/layout/Layout.tsx'
import ProtectedRoute from '@/components/layout/ProtectedRoute.tsx'
import PageLoader from '@/pages/PageLoader.tsx'
const HomePage = lazy(() => import('@/pages/HomePage'))
const LoginPage = lazy(() => import('@/pages/LoginPage'))
const LibraryPage = lazy(() => import('@/pages/LibraryPage'))
const UploadPage = lazy(() => import('@/pages/UploadPage'))
const WatchPage = lazy(() => import('@/pages/WatchPage'))

function App() {
  return (
    <BrowserRouter>
      <Suspense fallback={<PageLoader />}>
        <Routes>
          <Route element={<Layout />}>
            {/* Public */}
            <Route path="/" element={<HomePage />} />
            <Route path="/login" element={<LoginPage />} />

            {/* Protected */}
            <Route element={<ProtectedRoute />}>
              <Route path="/library" element={<LibraryPage />} />
              <Route path="/upload" element={<UploadPage />} />

              <Route path="/watch/:videoId" element={<WatchPage />} />
            </Route>
          </Route>
        </Routes>
      </Suspense>
    </BrowserRouter>
  )
}

export default App

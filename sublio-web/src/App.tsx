import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { lazy, Suspense } from 'react'
import { ErrorBoundary } from 'react-error-boundary'
import { Toaster } from 'sonner'
import Layout from '@/components/layout/Layout.tsx'
import ProtectedRoute from '@/components/layout/ProtectedRoute.tsx'
import PageLoader from '@/pages/PageLoader.tsx'
import PageErrorFallback from '@/components/ui/PageErrorFallback'

const HomePage = lazy(() => import('@/pages/HomePage'))
const LoginPage = lazy(() => import('@/pages/LoginPage'))
const LibraryPage = lazy(() => import('@/pages/LibraryPage'))
const UploadPage = lazy(() => import('@/pages/UploadPage'))
const WatchPage = lazy(() => import('@/pages/WatchPage'))

function App() {
  return (
    <BrowserRouter>
      <Toaster />
      <ErrorBoundary FallbackComponent={PageErrorFallback}>
        <Suspense fallback={<PageLoader />}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route element={<Layout />}>
              {/* Public */}
              <Route path="/" element={<HomePage />} />

              {/* Protected */}
              <Route element={<ProtectedRoute />}>
                <Route path="/library" element={<LibraryPage />} />
                <Route path="/upload" element={<UploadPage />} />

                <Route path="/watch/:videoId" element={<WatchPage />} />
              </Route>
            </Route>
          </Routes>
        </Suspense>
      </ErrorBoundary>
    </BrowserRouter>
  )
}

export default App

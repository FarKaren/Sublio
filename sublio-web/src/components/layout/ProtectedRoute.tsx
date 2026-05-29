import { useAuth } from '@/hooks/useAuth.ts'
import { Outlet, useLocation, Navigate } from 'react-router-dom'

export default function ProtectedRoute() {
  const { isAuthenticated } = useAuth()
  const location = useLocation()

  if (!isAuthenticated) {
    // Pass the attempted URL so we can redirect back after login
    return <Navigate to="/login" state={{ from: location }} replace />
  }

  return <Outlet />
}

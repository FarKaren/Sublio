import { useAuthStore } from '@/store/authStore.ts'
import { useNavigate } from 'react-router-dom'
import { authService } from '@/services/authService.ts'

export const useAuth = () => {
  const { user, accessToken } = useAuthStore()
  const navigate = useNavigate()

  return {
    user,
    isAuthenticated: !!accessToken,
    logout: async () => {
      await authService.logout()
      navigate('/login')
    },
  }
}

import { useAuthStore } from '@/store/authStore.ts'
import { authService } from '@/services/authService.ts'

export const useAuth = () => {
  const user = useAuthStore((state) => state.user)
  const accessToken = useAuthStore((state) => state.accessToken)

  return {
    user,
    isAuthenticated: !!accessToken,
    logout: () => authService.logout(),
  }
}

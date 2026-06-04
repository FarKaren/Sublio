import { instance } from '@/services/api.ts'
import { useAuthStore } from '@/store/authStore.ts'
import type { User } from '@/types'

export const authService = {
  login: async (email: string, password: string): Promise<{ user: User; accessToken: string }> => {
    const response = await instance.post(
      '/auth/login',
      { email, password },
      { withCredentials: true }
    )
    useAuthStore.getState().setAuth(response.data.user, response.data.accessToken)
    return response.data
  },

  register: async (
    email: string,
    password: string
  ): Promise<{ user: User; accessToken: string }> => {
    const response = await instance.post(
      '/auth/register',
      { email, password },
      { withCredentials: true }
    )
    useAuthStore.getState().setAuth(response.data.user, response.data.accessToken)
    return response.data
  },

  refresh: async (): Promise<void> => {
    const response = await instance.post('/auth/refresh', {}, { withCredentials: true })
    useAuthStore.getState().setAuth(response.data.user, response.data.accessToken)
    return response.data
  },

  logout: async (): Promise<void> => {
    await instance.post('/auth/logout', {}, { withCredentials: true })
    useAuthStore.getState().clearAuth()
  },
}

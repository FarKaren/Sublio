import { type CreateAxiosDefaults, type InternalAxiosRequestConfig } from 'axios'
import axios from 'axios'
import { useAuthStore } from '@/store/authStore.ts'

interface CustomAxiosRequestConfig extends InternalAxiosRequestConfig {
  _retry?: boolean
}

const baseConfig: CreateAxiosDefaults = {
  baseURL: `${import.meta.env.VITE_API_BASE_URL}`,
  timeout: 30_000,
}

export const instance = axios.create(baseConfig)

async function refreshAccessToken() {
  const response = await axios.post(
    `${import.meta.env.VITE_API_BASE_URL}/auth/refresh`,
    {},
    { withCredentials: true }
  )
  return response.data // { accessToken: string, user: User }
}

instance.interceptors.request.use(
  function (config) {
    const accessToken = useAuthStore.getState().accessToken

    if (accessToken) {
      config.headers.Authorization = `Bearer ${accessToken}`
    }

    return config
  },

  function (error) {
    return Promise.reject(error)
  }
)

instance.interceptors.response.use(
  function (response) {
    return response
  },

  async function (error) {
    const originalRequest: CustomAxiosRequestConfig | undefined = error.config

    if (error.response?.status === 401 && originalRequest && !originalRequest._retry) {
      originalRequest._retry = true

      try {
        const { accessToken, user } = await refreshAccessToken()

        useAuthStore.getState().setAuth(user, accessToken)

        originalRequest.headers.Authorization = `Bearer ${accessToken}`

        return instance(originalRequest)
      } catch (err) {
        useAuthStore.getState().clearAuth()
        window.location.href = '/login'
        return Promise.reject(err)
      }
    }
    return Promise.reject(error)
  }
)

import { useMutation } from '@tanstack/react-query'
import { authService } from '@/services/authService.ts'
import type { authSchema } from '@/utils/zod.ts'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import AuthForm from '@/components/ui/AuthForm.tsx'
import { z } from 'zod'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs.tsx'
import { useAuthStore } from '@/store/authStore.ts'

export default function LoginPage() {
  const isAuthenticated = useAuthStore((state) => !!state.accessToken)

  const navigate = useNavigate()
  const location = useLocation()

  const loginMutation = useMutation({
    mutationFn: (values: z.infer<typeof authSchema>) =>
      authService.login(values.email, values.password),
    onSuccess: () => navigate(location.state?.from?.pathname ?? '/'),
  })

  const registerMutation = useMutation({
    mutationFn: (values: z.infer<typeof authSchema>) =>
      authService.register(values.email, values.password),
    onSuccess: () => navigate('/'),
  })

  if (isAuthenticated) return <Navigate to="/" replace />

  return (
    <div className="min-h-screen flex items-center justify-center bg-background relative overflow-hidden">
      {/* Ambient glow blobs */}
      <div className="pointer-events-none absolute -top-32 left-1/2 -translate-x-1/2 h-[500px] w-[500px] rounded-full bg-primary/15 blur-[120px]" />
      <div className="pointer-events-none absolute -bottom-20 -right-20 h-[350px] w-[350px] rounded-full bg-accent/10 blur-[100px]" />

      <div className="relative z-10 w-full max-w-sm px-4">
        {/* Logo */}
        <div className="mb-8 text-center">
          <h1 className="text-4xl font-bold text-accent">Sublio</h1>
          <p className="mt-2 text-sm text-muted-foreground">字幕 · Japanese subtitle generator</p>
        </div>

        {/* Glassmorphism card */}
        <div className="rounded-2xl border border-primary/20 bg-card/60 p-6 shadow-2xl backdrop-blur-md">
          <Tabs defaultValue="signin">
            <TabsList className="w-full">
              <TabsTrigger value="signin" className="flex-1">
                Sign In
              </TabsTrigger>
              <TabsTrigger value="register" className="flex-1">
                Sign Up
              </TabsTrigger>
            </TabsList>
            <TabsContent value="signin">
              <AuthForm mutation={loginMutation} />
            </TabsContent>
            <TabsContent value="register">
              <AuthForm mutation={registerMutation} />
            </TabsContent>
          </Tabs>
        </div>
      </div>
    </div>
  )
}

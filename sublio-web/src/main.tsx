import { QueryClientProvider, QueryClient, QueryCache, MutationCache } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { toast } from 'sonner'
import '@fontsource/noto-sans-jp/400.css'
import '@fontsource/noto-sans-jp/500.css'
import './index.css'
import App from './App.tsx'
import { ReactQueryDevtools } from '@tanstack/react-query-devtools'

const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error) => toast.error(`Error: ${error.message}`),
  }),
  mutationCache: new MutationCache({
    onError: (error) => toast.error(`Error: ${error.message}`),
  }),
  defaultOptions: {
    queries: {
      staleTime: 1000 * 60 * 5, // 5 min — don't refetch unless stale
      retry: 1, // retry failed requests once
      refetchOnWindowFocus: false, // disable for video app (no surprising refetches)
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={queryClient}>
    <StrictMode>
      <App />
    </StrictMode>
    {import.meta.env.DEV && <ReactQueryDevtools initialIsOpen={false} />}
  </QueryClientProvider>
)

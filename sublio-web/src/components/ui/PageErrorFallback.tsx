import type { FallbackProps } from 'react-error-boundary'
import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'

export default function PageErrorFallback({ error, resetErrorBoundary }: FallbackProps) {
  const navigate = useNavigate()

  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4">
      <h1 className="text-2xl font-bold">Something went wrong</h1>
      {import.meta.env.DEV && (
        <pre className="max-w-lg overflow-auto rounded bg-muted p-4 text-sm text-muted-foreground">
          {error instanceof Error ? error.message : String(error)}
        </pre>
      )}
      <div className="flex gap-2">
        <Button onClick={() => window.location.reload()}>Reload page</Button>
        <Button
          variant="outline"
          onClick={() => {
            resetErrorBoundary()
            navigate('/')
          }}
        >
          Go home
        </Button>
      </div>
    </div>
  )
}

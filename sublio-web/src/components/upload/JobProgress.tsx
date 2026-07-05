import { type FC, useEffect } from 'react'
import useJobProgress from '@/hooks/useJobProgress.ts'
import { Badge } from '@/components/ui/badge.tsx'
import { Progress } from '@/components/ui/progress.tsx'
import { Button } from '@/components/ui/button.tsx'
import { cn } from '@/lib/utils'
import type { JobStatus } from '@/types'

interface JobProgressProps {
  jobId: string
  onDone: (videoId: string) => void
}

const STATUS_PERCENT: Record<JobStatus, number> = {
  CREATED: 0,
  QUEUED: 10,
  TRANSCRIBING: 40,
  PROCESSING: 80,
  DONE: 100,
  ERROR: 0,
}

const JobProgress: FC<JobProgressProps> = ({ jobId, onDone }) => {
  const { status, message, result } = useJobProgress(jobId)

  useEffect(() => {
    if (status === 'DONE' && result) {
      onDone(result.videoUrl)
    }
  }, [status, result, onDone])

  const isPulsing = status === 'TRANSCRIBING' || status === 'PROCESSING'
  const badgeVariant =
    status === 'ERROR' ? 'destructive' : status === 'DONE' ? 'secondary' : 'default'

  return (
    <div className="flex w-full flex-col gap-3">
      <Badge variant={badgeVariant} className={cn('w-fit', isPulsing && 'animate-pulse')}>
        {status}
      </Badge>
      <Progress value={STATUS_PERCENT[status]} aria-label="Processing progress" />
      {message && (
        <p
          role={status === 'ERROR' ? 'alert' : undefined}
          className={cn(
            'text-sm',
            status === 'ERROR' ? 'text-destructive' : 'text-muted-foreground'
          )}
        >
          {message}
        </p>
      )}
      {status === 'DONE' && result && (
        <Button asChild>
          <a href={result.videoUrl}>Watch</a>
        </Button>
      )}
      {status === 'ERROR' && (
        <Button variant="destructive" onClick={() => window.location.reload()}>
          Try again
        </Button>
      )}
    </div>
  )
}

export default JobProgress

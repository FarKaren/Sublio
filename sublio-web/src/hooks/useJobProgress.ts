import { useState, useEffect, useRef } from 'react'
import type { JobStatus } from '@/types'
import { jobService } from '@/services/jobService.ts'

function useJobProgress(jobId: string | null): {
  status: JobStatus
  message: string
  result: { videoUrl: string; subtitleId: string } | null
} {
  const [status, setStatus] = useState<JobStatus>('CREATED')
  const [message, setMessage] = useState('')
  const [result, setResult] = useState<{ videoUrl: string; subtitleId: string } | null>(null)
  const eventSourceRef = useRef<EventSource | null>(null)

  useEffect(() => {
    if (!jobId) return

    let cancelled = false

    const setUpSse = async () => {
      try {
        const { sseToken } = await jobService.getSseToken(jobId)
        if (cancelled) return

        const es = new EventSource(
          `${import.meta.env.VITE_API_BASE_URL}/api/jobs/${jobId}/progress?token=${sseToken}`
        )
        eventSourceRef.current = es

        es.addEventListener('progress', (event) => {
          const data = JSON.parse((event as MessageEvent).data)
          setStatus(data.status)
          setMessage(data.message ?? '')
        })

        es.addEventListener('done', (event) => {
          const data = JSON.parse((event as MessageEvent).data)
          setStatus('DONE')
          setResult(data.result)
          es.close()
        })

        // named "error" events sent by the server (event: error in the SSE stream)
        es.addEventListener('error', (event) => {
          if (!(event instanceof MessageEvent)) return
          const data = JSON.parse(event.data)
          setStatus('ERROR')
          setMessage(data.message ?? 'An error occurred')
          es.close()
        })

        // connection-level errors — token is now expired, reconnect with a fresh one
        es.onerror = () => {
          es.close()
          eventSourceRef.current = null
          if (!cancelled) setUpSse()
        }
      } catch {
        // getSseToken failed — surface nothing, let the job poll handle it
      }
    }

    setUpSse()

    return () => {
      cancelled = true
      eventSourceRef.current?.close()
      eventSourceRef.current = null
    }
  }, [jobId])

  return { status, message, result }
}

export default useJobProgress

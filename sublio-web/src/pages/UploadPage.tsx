import { useReducer, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import DropZone from '@/components/upload/DropZone.tsx'
import JobProgress from '@/components/upload/JobProgress.tsx'
import { Progress } from '@/components/ui/progress.tsx'
import { Button } from '@/components/ui/button.tsx'
import { jobService } from '@/services/jobService.ts'
import { mediaService } from '@/services/mediaService.ts'

type UploadState =
  | { phase: 'IDLE' }
  | {
      phase: 'UPLOADING'
      fileName: string
      fileSize: number
      progress: number
      bytesPerSec?: number
      etaSec?: number
    }
  | { phase: 'PROCESSING'; jobId: string }
  | { phase: 'DONE'; videoId: string }
  | { phase: 'ERROR'; message: string }

type Action =
  | { type: 'START_UPLOAD'; fileName: string; fileSize: number }
  | { type: 'SET_PROGRESS'; progress: number; bytesPerSec?: number; etaSec?: number }
  | { type: 'START_PROCESSING'; jobId: string }
  | { type: 'DONE'; videoId: string }
  | { type: 'ERROR'; message: string }
  | { type: 'RESET' }

function reducer(state: UploadState, action: Action): UploadState {
  switch (action.type) {
    case 'START_UPLOAD':
      return {
        phase: 'UPLOADING',
        fileName: action.fileName,
        fileSize: action.fileSize,
        progress: 0,
      }
    case 'SET_PROGRESS':
      if (state.phase !== 'UPLOADING') return state
      return {
        ...state,
        progress: action.progress,
        bytesPerSec: action.bytesPerSec,
        etaSec: action.etaSec,
      }
    case 'START_PROCESSING':
      return { phase: 'PROCESSING', jobId: action.jobId }
    case 'DONE':
      return { phase: 'DONE', videoId: action.videoId }
    case 'ERROR':
      return { phase: 'ERROR', message: action.message }
    case 'RESET':
      return { phase: 'IDLE' }
  }
}

function formatSpeed(bytesPerSec: number): string {
  if (bytesPerSec < 1024 * 1024) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`
  return `${(bytesPerSec / (1024 * 1024)).toFixed(1)} MB/s`
}

function formatEta(sec: number): string {
  if (sec < 60) return `${Math.round(sec)}s left`
  return `${Math.round(sec / 60)}m left`
}

export default function UploadPage() {
  const [state, dispatch] = useReducer(reducer, { phase: 'IDLE' })
  const navigate = useNavigate()
  const progressSnapshotRef = useRef<{ loadedBytes: number; timestamp: number } | null>(null)

  useEffect(() => {
    if (state.phase !== 'DONE') return
    const timer = setTimeout(() => navigate(`/watch/${state.videoId}`), 1500)
    return () => clearTimeout(timer)
  }, [state, navigate])

  const handleFileSelect = async (file: File) => {
    dispatch({ type: 'START_UPLOAD', fileName: file.name, fileSize: file.size })
    progressSnapshotRef.current = null

    try {
      const { jobId } = await jobService.createJob()

      await mediaService.upload(file, jobId, (percent) => {
        const loadedBytes = (percent / 100) * file.size
        const now = Date.now()

        let bytesPerSec: number | undefined
        let etaSec: number | undefined

        const snap = progressSnapshotRef.current
        if (snap) {
          const deltaBytes = loadedBytes - snap.loadedBytes
          const deltaMs = now - snap.timestamp
          if (deltaMs > 500 && deltaBytes > 0) {
            bytesPerSec = (deltaBytes / deltaMs) * 1000
            etaSec = (file.size - loadedBytes) / bytesPerSec
            progressSnapshotRef.current = { loadedBytes, timestamp: now }
          }
        } else {
          progressSnapshotRef.current = { loadedBytes, timestamp: now }
        }

        dispatch({ type: 'SET_PROGRESS', progress: percent, bytesPerSec, etaSec })
      })

      dispatch({ type: 'START_PROCESSING', jobId })
    } catch (err) {
      dispatch({ type: 'ERROR', message: err instanceof Error ? err.message : 'Upload failed' })
    }
  }

  return (
    <div className="mx-auto flex max-w-lg flex-col gap-6 py-10">
      <h1 className="text-2xl font-semibold">Upload video</h1>

      {state.phase === 'IDLE' && <DropZone onFileSelect={handleFileSelect} />}

      {state.phase === 'UPLOADING' && (
        <div className="flex flex-col gap-3">
          <p className="truncate text-sm text-muted-foreground">{state.fileName}</p>
          <Progress value={state.progress} aria-label="Upload progress" />
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>{state.progress}%</span>
            {state.bytesPerSec != null && state.etaSec != null && (
              <span>
                {formatSpeed(state.bytesPerSec)} · {formatEta(state.etaSec)}
              </span>
            )}
          </div>
        </div>
      )}

      {state.phase === 'PROCESSING' && (
        <JobProgress
          jobId={state.jobId}
          onDone={(videoId) => dispatch({ type: 'DONE', videoId })}
        />
      )}

      {state.phase === 'DONE' && (
        <div className="flex flex-col items-center gap-4 text-center">
          <p className="text-muted-foreground">Subtitles ready — redirecting…</p>
          <Button onClick={() => navigate(`/watch/${state.videoId}`)}>Watch now</Button>
        </div>
      )}

      {state.phase === 'ERROR' && (
        <div className="flex flex-col items-center gap-4 text-center">
          <p role="alert" className="text-sm text-destructive">
            {state.message}
          </p>
          <Button variant="outline" onClick={() => dispatch({ type: 'RESET' })}>
            Try again
          </Button>
        </div>
      )}
    </div>
  )
}

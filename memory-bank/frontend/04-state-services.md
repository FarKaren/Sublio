# Frontend 04 — State, Services and Hooks

## State architecture

```
Zustand (authStore)          ← global auth state (accessToken, user)
│
TanStack Query               ← server state (videos, subtitles)
│  useQuery / useMutation
│
Local useState               ← UI state (currentTime, uploadProgress)
│
useJobProgress (SSE)         ← realtime job state
```

---

## Zustand — authStore.ts

```typescript
// store/authStore.ts

interface AuthState {
  user: User | null
  accessToken: string | null
  setAuth: (user: User, token: string) => void
  clearAuth: () => void
}

// Persistence: accessToken is saved in localStorage
// via zustand/middleware persist

State:
  accessToken  — JWT string or null
  user         — { id, email } or null

Actions:
  setAuth(user, token) — called after successful login
  clearAuth()          — called on logout or 401
```

---

## TanStack Query — query keys

```typescript
['videos']                    ← list of all videos (LibraryPage, HomePage)
['subtitles', videoId]        ← subtitles for video (WatchPage)
['job', jobId]                ← job status (polling fallback)
```

---

## services/api.ts — axios instance

```typescript
// Create instance with base URL and JWT interceptor

const api = axios.create({
  baseURL: '/api',
  timeout: 30_000,
})

// Request interceptor: adds Bearer token
api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// Response interceptor: on 401 → refresh → retry
//   if refresh also 401 → clearAuth() → redirect /login
api.interceptors.response.use(
  (res) => res,
  async (err) => {
    if (err.response?.status === 401 && !err.config._retry) {
      err.config._retry = true
      await authService.refresh()    // updates token in store
      return api(err.config)         // retries original request
    }
    return Promise.reject(err)
  }
)
```

---

## services/authService.ts

```typescript
login(email, password)
  → POST /api/auth/login
  → authStore.setAuth(user, accessToken)
  → saves refreshToken in httpOnly cookie
    (or localStorage — depends on security requirements)

register(email, password)
  → POST /api/auth/register

refresh()
  → POST /api/auth/refresh  { refreshToken }
  → authStore.setAuth(...)

logout()
  → authStore.clearAuth()
  → navigate('/login')
```

---

## services/mediaService.ts

```typescript
getVideoList()
  → GET /api/video/list
  → returns: VideoInfo[]

upload(file: File, jobId: string, onProgress: (pct: number) => void)
  → POST /api/upload?jobId={jobId}
  → FormData { video: file }
  → axios onUploadProgress → onProgress(percent)

deleteVideo(folderId: string)
  → DELETE /api/video/{folderId}

streamUrl(folderId: string, fileName: string)
  → returns: `/api/video/${folderId}/${fileName}`
    (substituted into <video src>)
```

---

## services/jobService.ts

```typescript
createJob()
  → POST /api/jobs
  → returns: { jobId: string }

getJob(jobId: string)
  → GET /api/jobs/{jobId}
  → returns: { status, message, result? }

getSseToken(jobId: string)
  → POST /api/jobs/{jobId}/sse-token
  → returns: { sseToken: string }   ← short-lived token (60 sec)
  → used in useJobProgress
```

---

## services/subtitleService.ts

```typescript
getSubtitles(subtitleId: string)
  → GET /api/subtitles/{subtitleId}
  → returns: SubtitleEntry[]

SubtitleEntry:
  { start: string, end: string, kanji: string, hiragana: string }
```

---

## hooks/useJobProgress.ts — SSE

```
Problem: EventSource does not support custom headers
         → cannot send JWT via Authorization header

Solution: short-lived SSE token
  1. jobService.getSseToken(jobId) → sseToken (TTL 60 sec)
  2. new EventSource(`/api/jobs/${jobId}/progress?token=${sseToken}`)
  3. Gateway validates sseToken from query param (only for /progress)

Hook:
  function useJobProgress(jobId: string | null)

  Returns:
    status:  JobStatus  ('CREATED' | 'QUEUED' | 'TRANSCRIBING' | 'PROCESSING' | 'DONE' | 'ERROR')
    message: string
    result:  { videoUrl, subtitleId } | null

Lifecycle:
  useEffect(() => {
    if (!jobId) return
    let es: EventSource | null = null

    getSseToken(jobId).then(({ sseToken }) => {
      es = new EventSource(`/api/jobs/${jobId}/progress?token=${sseToken}`)

      es.addEventListener('progress', (e) => {
        const { status, message } = JSON.parse(e.data)
        setStatus(status)
        setMessage(message)
      })

      es.addEventListener('done', (e) => {
        const { videoUrl, subtitleId } = JSON.parse(e.data)
        setResult({ videoUrl, subtitleId })
        setStatus('DONE')
        es?.close()
      })

      es.addEventListener('error', () => {
        setStatus('ERROR')
        es?.close()
      })
    })

    return () => es?.close()   ← cleanup on unmount
  }, [jobId])
```

---

## hooks/useVideoPlayer.ts

```typescript
function useVideoPlayer(videoRef: RefObject<HTMLVideoElement>)

Returns:
  currentTime: number          ← updated via timeupdate event
  duration: number
  playing: boolean
  volume: number

  play()    → videoRef.current.play()
  pause()   → videoRef.current.pause()
  seek(t)   → videoRef.current.currentTime = t
  setVolume(v) → videoRef.current.volume = v

useEffect:
  const el = videoRef.current
  el.addEventListener('timeupdate', () => setCurrentTime(el.currentTime))
  el.addEventListener('durationchange', () => setDuration(el.duration))
  el.addEventListener('play', () => setPlaying(true))
  el.addEventListener('pause', () => setPlaying(false))
```

---

## hooks/useAuth.ts

```typescript
// Convenience wrapper over authStore

function useAuth() {
  const { user, accessToken, clearAuth } = useAuthStore()
  return {
    isAuthenticated: !!accessToken,
    user,
    logout: () => {
      clearAuth()
      navigate('/login')
    }
  }
}
```

---

## types/index.ts

```typescript
export interface User {
  id: string
  email: string
}

export interface VideoInfo {
  folderId: string
  videoName: string
  createdAt: string
}

export type JobStatus =
  | 'CREATED'
  | 'QUEUED'
  | 'TRANSCRIBING'
  | 'PROCESSING'
  | 'DONE'
  | 'ERROR'

export interface Job {
  id: string
  status: JobStatus
  message: string
  result?: {
    videoUrl: string
    subtitleId: string
  }
}

export interface SubtitleEntry {
  start: string      // "00:01:23,456"
  end: string
  kanji: string
  hiragana: string
}
```

---

## Subtitle time parsing (lib/utils.ts)

```typescript
// "00:01:23,456" → seconds (number)
export function parseSubtitleTime(srtTime: string): number {
  const [hms, ms] = srtTime.split(',')
  const [h, m, s] = hms.split(':').map(Number)
  return h * 3600 + m * 60 + s + Number(ms) / 1000
}
```

---

## package.json dependencies

```json
{
  "dependencies": {
    "react": "^18",
    "react-dom": "^18",
    "react-router-dom": "^6",
    "axios": "^1.6",
    "zustand": "^4",
    "@tanstack/react-query": "^5",
    "react-dropzone": "^14",
    "class-variance-authority": "^0.7",
    "clsx": "^2",
    "tailwind-merge": "^2",
    "lucide-react": "^0.400"
  },
  "devDependencies": {
    "typescript": "^5",
    "@types/react": "^18",
    "@types/react-dom": "^18",
    "vite": "^5",
    "@vitejs/plugin-react": "^4",
    "tailwindcss": "^3",
    "autoprefixer": "^10",
    "postcss": "^8"
  }
}
```

Icons: `lucide-react` — already integrated in shadcn/ui, no separate icon library needed.

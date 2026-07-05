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
```

Note: `['job', jobId]` polling fallback is not currently wired up — `getJob()` exists
on jobService but progress is driven entirely by `useJobProgress` (SSE), not useQuery.

---

## services/api.ts — axios instance

```typescript
// Create instance with base URL and JWT interceptor
// Exported as `instance` (not `api`) — all services import { instance } from '@/services/api'
// No Vite dev-proxy: baseURL comes straight from VITE_API_BASE_URL env var

export const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 30_000,
})

// Request interceptor: adds Bearer token
instance.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// Response interceptor: on 401 → refresh → retry
//   if refresh also 401 → clearAuth() → redirect /login
// Note: refresh is a local refreshAccessToken() in this file (raw axios.post to
// /auth/refresh with withCredentials), NOT a call to authService.refresh() —
// the two are currently duplicated logic.
instance.interceptors.response.use(
  (res) => res,
  async (err) => {
    if (err.response?.status === 401 && !err.config._retry) {
      err.config._retry = true
      const { accessToken, user } = await refreshAccessToken()
      useAuthStore.getState().setAuth(user, accessToken)
      return instance(err.config)    // retries original request
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
  → FormData { file: file }        ← field name is "file", not "video"
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
  2. new EventSource(`${VITE_API_BASE_URL}/api/jobs/${jobId}/progress?token=${sseToken}`)
     ← absolute URL (env-based baseURL), not a relative path
  3. Gateway validates sseToken from query param (only for /progress)

Hook:
  function useJobProgress(jobId: string | null)

  Returns:
    status:  JobStatus  ('CREATED' | 'QUEUED' | 'TRANSCRIBING' | 'PROCESSING' | 'DONE' | 'ERROR')
    message: string
    result:  { videoUrl, subtitleId } | null

Lifecycle (simplified):
  useEffect(() => {
    if (!jobId) return
    let es: EventSource | null = null

    connect()  // fetches a fresh sseToken and opens the EventSource

    es.addEventListener('progress', ...)
    es.addEventListener('done', ...)   → es?.close()
    es.addEventListener('error', () => {
      // on error (e.g. expired sseToken), fetches a NEW sseToken and
      // reconnects rather than immediately giving up — not just a hard failure
      es?.close()
      reconnect()
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
  buffered: number             ← buffered-progress percentage (not just playback state)

  play()    → videoRef.current.play()
  pause()   → videoRef.current.pause()
  seek(t)   → videoRef.current.currentTime = t
  setVolume(v) → videoRef.current.volume = v
  toggleFullscreen() → videoRef.current.requestFullscreen() / document.exitFullscreen()

useEffect:
  const el = videoRef.current
  el.addEventListener('timeupdate', () => setCurrentTime(el.currentTime))
  el.addEventListener('durationchange', () => setDuration(el.duration))
  el.addEventListener('play', () => setPlaying(true))
  el.addEventListener('pause', () => setPlaying(false))
  el.addEventListener('progress', () => setBuffered(...))
  // cleanup: removes all listeners on unmount
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
    "react": "^19",
    "react-dom": "^19",
    "react-router-dom": "^7",
    "axios": "^1.16",
    "zustand": "^5",
    "@tanstack/react-query": "^5",
    "react-dropzone": "^15",
    "react-hook-form": "^7",
    "@hookform/resolvers": "^5",
    "zod": "^3",
    "react-error-boundary": "^6",
    "sonner": "^2",
    "@fontsource-variable/geist": "^5",
    "@fontsource/noto-sans-jp": "^5",
    "next-themes": "^0.4",
    "radix-ui": "^1",
    "class-variance-authority": "^0.7",
    "clsx": "^2",
    "tailwind-merge": "^3",
    "lucide-react": "^1"
  },
  "devDependencies": {
    "typescript": "~6",
    "@types/react": "^19",
    "@types/react-dom": "^19",
    "vite": "^8",
    "@vitejs/plugin-react": "^6",
    "@tailwindcss/vite": "^4",
    "tailwindcss": "^4",
    "rollup-plugin-visualizer": "^7",
    "babel-plugin-react-compiler": "^1"
  }
}
```

Note: Tailwind v4 uses the `@tailwindcss/vite` plugin, no `postcss`/`autoprefixer`/config file needed.
Icons: `lucide-react` — already integrated in shadcn/ui, no separate icon library needed.

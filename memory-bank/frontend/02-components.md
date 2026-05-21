# Frontend 02 — Components

## shadcn/ui components (installed via CLI)

```bash
npx shadcn@latest add button card progress badge dialog slider scroll-area input label separator toast
```

```
Component         Where used
──────────────────────────────────────────────────────────────────
Button            everywhere — actions
Card              VideoCard, LoginPage, JobProgress
Progress          JobProgress (transcription progress)
Badge             JobProgress (status: QUEUED, TRANSCRIBING, DONE)
Dialog            confirm video deletion in VideoCard
Slider            PlayerControls: timeline + volume
ScrollArea        SubtitlePanel (subtitle scrolling)
Input             LoginPage form
Label             LoginPage form
Separator         Layout (horizontal dividers)
Toast / Toaster   global error notifications
```

---

## Layout components

### Layout.tsx

```
┌──────────────────────────────────────────────────────┐
│  Header                                              │
│  ┌────────────────────────────────────────────────┐  │
│  │  🎌 Sublio   Library   Upload   [Logout/Login] │  │
│  └────────────────────────────────────────────────┘  │
│                                                      │
│  <Outlet />   ← current page                         │
│                                                      │
└──────────────────────────────────────────────────────┘
```

### Header.tsx

- Logo "🎌 Sublio" → link to `/`
- Nav: Library → `/library`, Upload → `/upload`
- If authenticated: avatar + Logout button
- If not: Login button → `/login`
- Auth state read from `useAuth()` (Zustand)

### ProtectedRoute.tsx

```
If no accessToken in authStore → <Navigate to="/login" />
Otherwise → <Outlet />
```

---

## Upload components

### DropZone.tsx

```
┌──────────────────────────────────────────────────┐
│                                                  │
│   📁  Drag video here                            │
│       or click to select                         │
│                                                  │
│   Supported: MP4, MKV, AVI (up to 2GB)          │
│                                                  │
└──────────────────────────────────────────────────┘

Props:
  onFileSelect: (file: File) => void

Dependency: react-dropzone
  accept: { 'video/*': ['.mp4', '.mkv', '.avi'] }
  maxSize: 2 * 1024 * 1024 * 1024  // 2GB
  multiple: false

UI states:
  idle      → dashed border, gray
  drag-over → border highlighted in purple
  rejected  → red border, error message
```

### JobProgress.tsx

```
Props:
  jobId: string

Uses: useJobProgress(jobId)

┌──────────────────────────────────────────────────┐
│  [Badge: TRANSCRIBING]                           │
│                                                  │
│  Transcribing Japanese audio...                  │
│                                                  │
│  ████████████░░░░░░░░  60%                       │
│  shadcn Progress                                 │
│                                                  │
└──────────────────────────────────────────────────┘

Status → Badge variant:
  CREATED          → secondary  (gray)
  QUEUED           → outline    (outlined)
  TRANSCRIBING     → default    (purple, pulsing)
  PROCESSING       → default    (purple, pulsing)
  DONE             → success    (green)
  ERROR            → destructive (red)

Progress by status:
  QUEUED           →  10%
  TRANSCRIBING     →  40%
  PROCESSING       →  80%
  DONE             → 100%
  ERROR            →   0% (red)

On DONE: show "Watch" button → navigate(/watch/:videoId)
```

---

## Player components

### VideoPlayer.tsx

```
Props:
  src: string                  ← video URL (/api/video/...)
  subtitles: SubtitleEntry[]
  onTimeUpdate: (t: number) => void

Inside:
  const videoRef = useRef<HTMLVideoElement>()

  ┌──────────────────────────────────────────┐
  │  <video ref={videoRef} preload="metadata">
  │    <source src={src} type="video/mp4" />
  │  </video>
  │                                          │
  │  <SubtitleOverlay                        │
  │    currentTime={currentTime}             │
  │    subtitles={subtitles}                 │
  │  />                                      │
  └──────────────────────────────────────────┘
  <PlayerControls
    videoRef={videoRef}
    currentTime={currentTime}
    duration={duration}
  />

Important: video without native controls (controls removed),
all control via PlayerControls
```

### SubtitleOverlay.tsx

```
Props:
  currentTime: number
  subtitles: SubtitleEntry[]

Logic:
  const active = subtitles.find(s =>
    parseTime(s.start) <= currentTime &&
    currentTime <= parseTime(s.end)
  )

Visually (over video, position: absolute bottom):

  ┌──────────────────────────────────────────┐
  │  [video frame]                           │
  │                                          │
  │  ┌────────────────────────────────────┐  │
  │  │  なにをしているんだ               │  │  ← kanji
  │  │  なにをしているんだ               │  │  ← hiragana (smaller)
  │  └────────────────────────────────────┘  │
  └──────────────────────────────────────────┘

CSS:
  kanji:    font-size: 1.3rem, font-family: "Noto Sans JP", white, shadow
  hiragana: font-size: 0.9rem, opacity: 0.8, font-family: "Noto Sans JP"
  background: rgba(0,0,0,0.6), border-radius: 4px, padding: 4px 12px
```

### PlayerControls.tsx

```
Props:
  videoRef: RefObject<HTMLVideoElement>
  currentTime: number
  duration: number

┌────────────────────────────────────────────────────────┐
│  ▶  00:01:23 ──────────────●───────────── 23:58       │
│              shadcn Slider (timeline)                  │
│                                                        │
│  🔊 ──────●──────  [⛶ fullscreen]  [CC subtitles on] │
│     Slider(volume)                                     │
└────────────────────────────────────────────────────────┘

Actions:
  play/pause  → videoRef.current.play() / .pause()
  seek        → Slider onValueChange → videoRef.current.currentTime = val
  volume      → videoRef.current.volume = val
  fullscreen  → videoRef.current.requestFullscreen()
```

### SubtitlePanel.tsx

```
Props:
  subtitles: SubtitleEntry[]
  currentTime: number
  onSeek: (time: number) => void

┌──────────────────────────────┐
│  Subtitles                   │
│  ┌────────────────────────┐  │
│  │ 00:00:12               │  │
│  │ 君の名は               │  ← active (highlighted)
│  │ きみのなは             │  │
│  └────────────────────────┘  │
│  ┌────────────────────────┐  │
│  │ 00:00:18               │  │
│  │ なにを...              │  │
│  │ なにを...              │  │
│  └────────────────────────┘  │
│  ...                         │
└──────────────────────────────┘
shadcn ScrollArea (height = player height)

On entry click → onSeek(parseTime(entry.start))
Auto-scroll to active entry via useEffect + scrollIntoView
```

---

## Library components

### VideoCard.tsx

```
Props:
  video: VideoInfo
  onDelete: (id: string) => void

shadcn Card:

┌────────────────────────┐
│  [preview / icon 🎬]   │
├────────────────────────┤
│  AnimePahe_Another.mp4 │
│  May 17 2026, 14:32    │
├────────────────────────┤
│  [▶ Watch]  [🗑 Delete] │
└────────────────────────┘

Delete → shadcn Dialog (confirm):
  "Delete video and subtitles? This action is irreversible."
  [Cancel] [Delete]
```

### VideoGrid.tsx

```
Props:
  videos: VideoInfo[]

CSS Grid, 3 columns on wide, 2 on md, 1 on mobile:
  grid-cols-1 md:grid-cols-2 lg:grid-cols-3
  gap-4

States:
  loading → skeleton cards (shadcn Skeleton)
  empty   → "No uploaded videos. Upload the first one!"
  error   → shadcn Toast with message
```

# Frontend 03 — Pages and Routing

## Routes

```
/                   HomePage           public
/login              LoginPage          public (redirect → / if already logged in)
/library            LibraryPage        protected (ProtectedRoute)
/upload             UploadPage         protected
/watch/:videoId     WatchPage          protected
```

---

## Routing schema (App.tsx)

```
<BrowserRouter>
  <Routes>
    <Route element={<Layout />}>

      ← public
      <Route path="/"        element={<HomePage />} />
      <Route path="/login"   element={<LoginPage />} />

      ← protected (ProtectedRoute checks JWT)
      <Route element={<ProtectedRoute />}>
        <Route path="/library"          element={<LibraryPage />} />
        <Route path="/upload"           element={<UploadPage />} />
        <Route path="/watch/:videoId"   element={<WatchPage />} />
      </Route>

    </Route>
  </Routes>
</BrowserRouter>
```

---

## Pages

### HomePage (`/`)

```
UX:
  If NOT authenticated:
    Hero: title + description + "Get Started" button → /login

  If authenticated:
    Hero (smaller) + "Upload Video" button → /upload
    Last 3 videos (VideoGrid, 3 cards)
    "See all" button → /library

Data:
  useQuery(['videos'], mediaService.getVideoList)
  take first 3 elements
```

---

### LoginPage (`/login`)

```
If already authenticated → <Navigate to="/" />

UI:
┌──────────────────────────────────────┐
│                                      │
│           🎌 Sublio                  │
│                                      │
│  ┌────────────────────────────────┐  │
│  │           Sign In              │  │
│  │                                │  │
│  │  Email                         │  │
│  │  [input]                       │  │
│  │                                │  │
│  │  Password                      │  │
│  │  [input type=password]         │  │
│  │                                │  │
│  │       [Sign In]                │  │
│  │                                │  │
│  │  No account? Register          │  │
│  └────────────────────────────────┘  │
│                                      │
└──────────────────────────────────────┘

Logic:
  useState: { email, password, error, loading }
  onSubmit → authService.login(email, password)
    → authStore.setToken(accessToken)
    → navigate('/')
  Error 401 → "Invalid email or password"
  "Register" tab switches form to register
```

---

### LibraryPage (`/library`)

```
UI:
┌──────────────────────────────────────────────────────────┐
│  Library (5 videos)                     [+ Upload]       │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐              │
│  │VideoCard │  │VideoCard │  │VideoCard │              │
│  └──────────┘  └──────────┘  └──────────┘              │
│  ┌──────────┐  ┌──────────┐                             │
│  │VideoCard │  │VideoCard │                             │
│  └──────────┘  └──────────┘                             │
└──────────────────────────────────────────────────────────┘

Data:
  useQuery(['videos'], mediaService.getVideoList)
  useMutation(mediaService.deleteVideo, {
    onSuccess: → queryClient.invalidateQueries(['videos'])
  })

States:
  loading → VideoGrid with skeleton
  empty   → illustration + "Upload your first video"
  error   → Toast "Failed to load list"
```

---

### UploadPage (`/upload`)

```
Page state (state machine):

  IDLE → UPLOADING → PROCESSING → DONE
                               → ERROR

UI by state:

  IDLE:
  ┌──────────────────────────────────────────┐
  │  Upload video                            │
  │  ┌────────────────────────────────────┐  │
  │  │         DropZone                   │  │
  │  └────────────────────────────────────┘  │
  └──────────────────────────────────────────┘

  UPLOADING (file selected, uploading):
  ┌──────────────────────────────────────────┐
  │  AnimePahe_Another.mp4  (852 MB)         │
  │  Uploading...  ██████████░░░░  70%       │
  │  (axios onUploadProgress → state)        │
  └──────────────────────────────────────────┘

  PROCESSING (file uploaded, processing):
  ┌──────────────────────────────────────────┐
  │  JobProgress (jobId)                     │
  │  [Badge: TRANSCRIBING]                   │
  │  Transcribing Japanese audio...          │
  │  ████████░░░░░░  40%                     │
  └──────────────────────────────────────────┘

  DONE:
  ┌──────────────────────────────────────────┐
  │  ✅ Subtitles are ready!                 │
  │                                          │
  │  [▶ Watch]    [Upload another]           │
  └──────────────────────────────────────────┘

  ERROR:
  ┌──────────────────────────────────────────┐
  │  ❌ Processing error                     │
  │  Error message                           │
  │  [Try again]                             │
  └──────────────────────────────────────────┘

Logic:
  1. onFileSelect → jobService.createJob() → jobId
  2. mediaService.upload(file, jobId, onProgress)
  3. useJobProgress(jobId) → watch status
  4. On DONE → navigate(`/watch/${videoId}`)
```

---

### WatchPage (`/watch/:videoId`)

```
const { videoId } = useParams()
const { data: subtitles } = useQuery(['subtitles', videoId], ...)

UI (desktop):
┌──────────────────────────────────────────────────────────────┐
│  ← Back                                AnimePahe_Another.mp4 │
├─────────────────────────────────┬────────────────────────────┤
│                                 │  Subtitles                 │
│     VideoPlayer                 │  ┌──────────────────────┐  │
│     ┌──────────────────────┐    │  │ 00:00:12 (active)    │  │
│     │   [video frame]      │    │  │ 君の名は              │  │
│     │                      │    │  │ きみのなは            │  │
│     │ [SubtitleOverlay]    │    │  └──────────────────────┘  │
│     └──────────────────────┘    │  ┌──────────────────────┐  │
│     [PlayerControls]            │  │ 00:00:18             │  │
│                                 │  │ なにを...            │  │
│                                 │  └──────────────────────┘  │
│                                 │  ...                        │
└─────────────────────────────────┴────────────────────────────┘

UI (mobile):
  VideoPlayer + SubtitleOverlay (full width)
  PlayerControls
  SubtitlePanel (below, scrollable)

currentTime is synchronized:
  VideoPlayer.onTimeUpdate → useState(currentTime)
  SubtitleOverlay ← currentTime
  SubtitlePanel   ← currentTime (highlight + auto-scroll)
  SubtitlePanel.onSeek → videoRef.current.currentTime = t
```

---

## UX flows

### First visit (not authenticated)

```
/ → "Get Started" button → /login → form → POST /api/auth/login
  → token in Zustand → redirect → /
```

### Full upload and watch flow

```
/upload
  → select file (DropZone)
  → POST /api/jobs                    (createJob)
  → POST /api/upload?jobId=xxx        (upload with progress bar)
  → SSE /api/jobs/:id/progress        (useJobProgress)
  → wait for DONE
  → navigate /watch/:videoId
  → GET /api/subtitles/:id
  → watch with subtitles
```

### Delete video

```
/library → VideoCard → [Delete] → Dialog confirm
  → DELETE /api/video/:folderId
  → queryClient.invalidateQueries(['videos'])
  → VideoGrid updated
```

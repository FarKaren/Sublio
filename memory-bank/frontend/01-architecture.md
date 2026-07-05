# Frontend 01 — sublio-web Architecture

## Stack

```
React 19 + TypeScript 6
Vite 8                        ← build (rollup-plugin-visualizer for bundle analysis)
Tailwind CSS v4               ← styling (@tailwindcss/vite plugin, no config file)
shadcn/ui (Nova preset)       ← UI components (Radix UI + Tailwind)
  └── lucide-react            ← icons
  └── next-themes             ← dark/light theme provider
React Router v7               ← routing (lazy-loaded pages + Suspense)
Zustand                       ← global state (auth, persisted)
TanStack Query v5             ← server state (videos, subtitles)
axios                         ← HTTP client + JWT interceptor, baseURL from VITE_API_BASE_URL
react-hook-form + zod         ← form state + validation (AuthForm)
react-error-boundary           ← global + per-route error boundaries
sonner                        ← toast notifications
react-dropzone                ← drag-and-drop file upload
@fontsource/noto-sans-jp      ← self-hosted Japanese font (FAR-42, avoids Google Fonts FOIT)
```

---

## Project tree

```
sublio-web/
│
├── public/
│   └── favicon.ico
│
├── src/
│   │
│   ├── components/
│   │   │
│   │   ├── ui/                         ← shadcn/ui (auto-generated, do not edit)
│   │   │   ├── alert-dialog.tsx        ← confirm dialogs (used for delete video, not Dialog)
│   │   │   ├── AuthForm.tsx            ← shared login/register form (react-hook-form + zod)
│   │   │   ├── badge.tsx
│   │   │   ├── button.tsx
│   │   │   ├── card.tsx
│   │   │   ├── dialog.tsx              ← scaffolded, unused (0 bytes) — AlertDialog used instead
│   │   │   ├── form.tsx
│   │   │   ├── input.tsx
│   │   │   ├── label.tsx
│   │   │   ├── PageErrorFallback.tsx   ← fallback UI for react-error-boundary
│   │   │   ├── progress.tsx
│   │   │   ├── scroll-area.tsx
│   │   │   ├── separator.tsx           ← scaffolded, unused (0 bytes)
│   │   │   ├── sheet.tsx               ← mobile nav drawer (Header)
│   │   │   ├── skeleton.tsx            ← loading placeholders (VideoGrid)
│   │   │   ├── slider.tsx
│   │   │   ├── sonner.tsx              ← toast notifications (replaces deprecated shadcn toast)
│   │   │   └── tabs.tsx                ← used in LoginPage (login/register tabs)
│   │   │
│   │   ├── layout/
│   │   │   ├── Layout.tsx              ← wrapper for protected pages (Header + <Outlet>)
│   │   │   ├── Header.tsx              ← nav + auth buttons + mobile Sheet menu
│   │   │   └── ProtectedRoute.tsx      ← redirect to /login if no token
│   │   │
│   │   ├── upload/
│   │   │   ├── DropZone.tsx            ← drag-and-drop zone (react-dropzone)
│   │   │   └── JobProgress.tsx         ← status + progress (shadcn Progress + Badge)
│   │   │
│   │   ├── player/
│   │   │   ├── VideoPlayer.tsx         ← HTML5 <video> wrapper
│   │   │   ├── PlayerControls.tsx      ← play/pause/seek/volume (shadcn Slider)
│   │   │   ├── SubtitleOverlay.tsx     ← kanji + hiragana over video
│   │   │   └── SubtitlePanel.tsx       ← subtitle list on the side (shadcn ScrollArea)
│   │   │
│   │   └── library/
│   │       ├── VideoCard.tsx           ← shadcn Card: preview + actions
│   │       └── VideoGrid.tsx           ← CSS grid of cards
│   │
│   ├── pages/
│   │   ├── HomePage.tsx                ← /
│   │   ├── LoginPage.tsx               ← /login
│   │   ├── LibraryPage.tsx             ← /library
│   │   ├── UploadPage.tsx              ← /upload
│   │   └── WatchPage.tsx               ← /watch/:videoId
│   │
│   ├── hooks/
│   │   ├── useJobProgress.ts           ← SSE EventSource, reconnects on token expiry
│   │   ├── useVideoPlayer.ts           ← currentTime, duration, playing, buffered, fullscreen
│   │   └── useAuth.ts                  ← user/isAuthenticated from Zustand + logout()
│   │
│   ├── services/
│   │   ├── api.ts                      ← axios instance + JWT interceptor
│   │   ├── authService.ts              ← login, register, refresh
│   │   ├── mediaService.ts             ← upload, getVideoList, deleteVideo
│   │   ├── jobService.ts               ← createJob, getJob, getSseToken
│   │   └── subtitleService.ts          ← getSubtitles
│   │
│   ├── store/
│   │   └── authStore.ts                ← Zustand: user, accessToken
│   │
│   ├── types/
│   │   └── index.ts                    ← SubtitleEntry, Job, VideoInfo, User
│   │
│   ├── lib/
│   │   └── utils.ts                    ← cn() shadcn utility
│   │
│   ├── App.tsx                         ← Router + QueryClientProvider
│   └── main.tsx                        ← ReactDOM.render + Providers
│
├── index.html                          ← class="dark", no font links (fonts self-hosted, imported in main.tsx)
├── vite.config.ts                      ← @tailwindcss/vite, react-compiler babel, bundle visualizer plugin
├── components.json                     ← shadcn config (Nova preset, Radix, cssVariables)
├── tsconfig.json
└── package.json
```

---

## Component diagram and relationships

```
App
├── ErrorBoundary (react-error-boundary → PageErrorFallback)
├── QueryClientProvider (TanStack Query)
├── Sonner (global toast notifications)
└── BrowserRouter
    ├── /login → LoginPage             ← NOT wrapped in Layout (no Header/chrome)
    │   └── AuthForm (Tabs: login/register, react-hook-form + zod)
    │
    └── Layout (Header + <Outlet>, all lazy-loaded via Suspense/PageLoader)
        ├── Header
        │   ├── nav links (desktop)
        │   ├── Sheet (mobile hamburger menu)
        │   └── [auth state] → Login button / Logout button
        │
        ├── / → HomePage
        │   ├── Hero section
        │   └── VideoGrid (last 3) → VideoCard × N
        │
        ├── /library → LibraryPage  [ProtectedRoute]
        │   └── VideoGrid → VideoCard × N (Skeleton while loading)
        │       └── shadcn AlertDialog (confirm delete)
        │
        ├── /upload → UploadPage  [ProtectedRoute]
        │   ├── DropZone
        │   └── JobProgress
        │       ├── shadcn Progress (progress bar)
        │       └── shadcn Badge (status)
        │
        └── /watch/:videoId → WatchPage  [ProtectedRoute]
            ├── VideoPlayer
            │   ├── <video> element
            │   ├── SubtitleOverlay (over video)
            │   └── PlayerControls
            │       ├── shadcn Slider (timeline)
            │       └── shadcn Slider (volume)
            └── SubtitlePanel
                └── shadcn ScrollArea
                    └── subtitle entries × N
```

---

## Visual design

```
Theme: dark (dark mode by default)

Color scheme (CSS variables in src/index.css @theme block — Tailwind v4 style):
  background:   #0a0a0f   ← near black with blue tint
  foreground:   #e8e8f0
  primary:      #7c5cbf   ← purple (anime aesthetic)
  secondary:    #1e1e2e
  accent:       #a78bfa   ← light purple
  muted:        #2d2d3f
  destructive:  #ef4444   ← red for errors

Fonts (self-hosted via @fontsource, imported in main.tsx — not Google Fonts):
  UI text:      Geist Variable (@fontsource-variable/geist)
  Japanese:     Noto Sans JP 400/500 (@fontsource/noto-sans-jp) ← for kanji + hiragana
```

---

## API base URL

No Vite dev-proxy is configured. `services/api.ts` sets `baseURL: import.meta.env.VITE_API_BASE_URL`
directly on the axios instance, so the gateway URL is supplied via env var (`.env`/`.env.local`,
not committed) rather than proxied through Vite.

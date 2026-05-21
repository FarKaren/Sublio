# Frontend 01 — sublio-web Architecture

## Stack

```
React 18 + TypeScript 5
Vite 5                   ← build, dev-proxy
Tailwind CSS v3          ← styling (required by shadcn)
shadcn/ui                ← UI components (Radix UI + Tailwind)
React Router v6          ← routing
Zustand                  ← global state (auth)
TanStack Query v5        ← server state (videos, subtitles)
axios                    ← HTTP client + JWT interceptor
react-dropzone           ← drag-and-drop file upload
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
│   │   │   ├── button.tsx
│   │   │   ├── card.tsx
│   │   │   ├── progress.tsx
│   │   │   ├── badge.tsx
│   │   │   ├── dialog.tsx
│   │   │   ├── slider.tsx
│   │   │   ├── scroll-area.tsx
│   │   │   ├── input.tsx
│   │   │   ├── label.tsx
│   │   │   ├── separator.tsx
│   │   │   ├── toast.tsx
│   │   │   └── toaster.tsx
│   │   │
│   │   ├── layout/
│   │   │   ├── Layout.tsx              ← wrapper for all pages
│   │   │   ├── Header.tsx              ← navigation + auth buttons
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
│   │   ├── useJobProgress.ts           ← SSE EventSource
│   │   ├── useVideoPlayer.ts           ← currentTime, duration, playing
│   │   └── useAuth.ts                  ← token from Zustand
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
├── index.html
├── vite.config.ts                      ← proxy /api → gateway:8080
├── tailwind.config.ts                  ← dark theme, shadcn preset
├── components.json                     ← shadcn config
├── tsconfig.json
└── package.json
```

---

## Component diagram and relationships

```
App
├── QueryClientProvider (TanStack Query)
├── Toaster (shadcn, global)
└── BrowserRouter
    ├── Layout
    │   ├── Header
    │   │   ├── nav links
    │   │   └── [auth state] → Login button / Logout button
    │   └── <Outlet> (page)
    │
    ├── / → HomePage
    │   ├── Hero section
    │   └── VideoGrid (last 3) → VideoCard × N
    │
    ├── /login → LoginPage
    │   └── shadcn Card (form)
    │
    ├── /library → LibraryPage  [ProtectedRoute]
    │   └── VideoGrid → VideoCard × N
    │       └── shadcn Dialog (confirm delete)
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

Color scheme (CSS variables in tailwind.config.ts):
  background:   #0a0a0f   ← near black with blue tint
  foreground:   #e8e8f0
  primary:      #7c5cbf   ← purple (anime aesthetic)
  secondary:    #1e1e2e
  accent:       #a78bfa   ← light purple
  muted:        #2d2d3f
  destructive:  #ef4444   ← red for errors

Fonts:
  UI text:      Inter (latin)
  Japanese:     Noto Sans JP (Google Fonts) ← for kanji + hiragana
```

---

## Vite proxy (dev)

```typescript
// vite.config.ts
export default defineConfig({
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',   // api-gateway
        changeOrigin: true,
      }
    }
  }
})
```

In production: Nginx or Gateway serves static files itself.

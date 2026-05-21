# Sublio — Claude Memory Hub

Sublio is a service for automatic generation of Japanese subtitles for anime and video.
Upload a video → get kanji + hiragana subtitles synchronized with the video player.

Predecessor project: `~/Desktop/jimakutsukeru` (Kotlin monolith).

---

## Memory-bank navigation

### General
| File | Contents |
|------|-----------|
| [00-overview.md](memory-bank/00-overview.md) | Monorepo tree, service diagram, networks, routing |

### Backend
| File | Contents |
|------|-----------|
| [backend/01-services.md](memory-bank/backend/01-services.md) | Detailed description of each of the 7 microservices |
| [backend/02-data-flow.md](memory-bank/backend/02-data-flow.md) | Sequence diagram, Redis channels, PostgreSQL schema |
| [backend/03-security.md](memory-bank/backend/03-security.md) | Security by layers, threat table |
| [backend/04-tech-stack.md](memory-bank/backend/04-tech-stack.md) | Languages, libraries, ports, Docker networks |
| [backend/05-impl-plan.md](memory-bank/backend/05-impl-plan.md) | 7 implementation phases with dependency diagram |

### Frontend
| File | Contents |
|------|-----------|
| [frontend/01-architecture.md](memory-bank/frontend/01-architecture.md) | Project tree, shadcn stack, component diagram |
| [frontend/02-components.md](memory-bank/frontend/02-components.md) | All components: shadcn list, UI diagrams, props |
| [frontend/03-pages-routing.md](memory-bank/frontend/03-pages-routing.md) | Pages, routes, UX flows, state machines |
| [frontend/04-state-services.md](memory-bank/frontend/04-state-services.md) | Zustand, TanStack Query, hooks, SSE, axios, types |

### Session summaries
| File | Contents |
|------|-----------|
| [sessions/_index.md](memory-bank/sessions/_index.md) | Index of all session summaries |

---

## Roles & collaboration style

### Frontend (React/TypeScript)
- **My role:** mentor / team lead
- I create and manage tasks/issues in **Linear** (via the Linear MCP server)
- I do **code review** and give advice — I do not write frontend code on my own initiative
- If you are stuck, you can explicitly ask me to write the code

### Backend (Go / Kotlin / Python)
- **My role:** colleague / consultant
- You handle backend implementation; I advise when asked
- You can also ask me to write backend code at any time

---

## Session summary workflow

**When the user says "summarize session" / "save session" / similar:**
1. Write a summary file: `memory-bank/sessions/YYYY-MM-DD_N.md` (N = sequence number if multiple on same day)
2. Add a one-line entry to `memory-bank/sessions/_index.md`
3. Confirm to the user that it was saved

**At the start of a new conversation** (when context seems fresh / user greets or asks where we left off):
1. Read `memory-bank/sessions/_index.md`
2. Present the list of past sessions to the user
3. Ask if they want to load one — if yes, read that file and use it as context

**Summary file format (`YYYY-MM-DD_N.md`):**
```
# Session YYYY-MM-DD — <short title>

## What we worked on
<2-4 bullet points>

## Decisions made
<bullet points, or "none">

## Current state / where we left off
<1 paragraph>

## Next steps
<bullet points>
```

---

## Current project state

```
sublio-media-service/   ← Go, skeleton (main.go — empty hello world)
sublio-web/             ← React/TS, directory created, empty
```

The remaining 5 services need to be created.

---

## Key decisions (do not change without reason)

**Backend:**
- **Go** — Gateway, Auth, Media, Job services (IO-bound, goroutines)
- **Kotlin** — Subtitle Service (Kuromoji — Java library, JVM required)
- **Python** — Transcription Worker (Faster-Whisper, no alternative)
- **Redis** — task queue (BLPOP) + pub/sub for SSE progress
- **PostgreSQL** — persistence (jobs, users, subtitles)
- Shared Docker Volume for file transfer (video → SRT)
- All external requests ONLY through api-gateway

**Frontend:**
- **shadcn/ui** — UI components (do not switch to another library)
- **Zustand** — only for auth state (accessToken)
- **TanStack Query** — server state (videos, subtitles)
- **SSE token** — short-lived token for EventSource (can't use JWT in header)
- **Noto Sans JP** — font for Japanese text
- Dark theme by default, accent — purple (#7c5cbf)

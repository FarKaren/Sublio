import { parseSubtitleTime } from '@/lib/utils'
import type { SubtitleEntry } from '@/types'

interface SubtitleOverlayProps {
  currentTime: number
  subtitles: SubtitleEntry[]
}

export function SubtitleOverlay({ currentTime, subtitles }: SubtitleOverlayProps) {
  const active = subtitles.find(
    (s) => parseSubtitleTime(s.start) <= currentTime && currentTime <= parseSubtitleTime(s.end)
  )

  if (!active) return null

  return (
    <div className="absolute bottom-8 left-0 right-0 flex justify-center pointer-events-none">
      <div className="bg-black/60 rounded px-3 py-1 text-center">
        <p
          className="text-white font-medium"
          style={{
            fontFamily: '"Noto Sans JP", sans-serif',
            fontSize: '1.3rem',
            textShadow: '1px 1px 2px rgba(0,0,0,0.9)',
          }}
        >
          {active.kanji}
        </p>
        <p
          className="text-white/80"
          style={{
            fontFamily: '"Noto Sans JP", sans-serif',
            fontSize: '0.9rem',
          }}
        >
          {active.hiragana}
        </p>
      </div>
    </div>
  )
}

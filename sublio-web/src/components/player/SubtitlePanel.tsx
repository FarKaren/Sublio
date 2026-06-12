import { useEffect, useRef } from 'react'
import { ScrollArea } from '@/components/ui/scroll-area'
import { cn, formatTime, parseSubtitleTime } from '@/lib/utils'
import type { SubtitleEntry } from '@/types'

interface SubtitlePanelProps {
  subtitles: SubtitleEntry[]
  currentTime: number
  onSeek: (t: number) => void
}

export function SubtitlePanel({ subtitles, currentTime, onSeek }: SubtitlePanelProps) {
  const activeIndex = subtitles.findIndex(
    (s) => parseSubtitleTime(s.start) <= currentTime && currentTime <= parseSubtitleTime(s.end)
  )
  const activeRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    activeRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }, [activeIndex])

  return (
    <div className="flex flex-col h-full">
      <p className="px-3 py-2 text-sm font-medium text-muted-foreground shrink-0">Subtitles</p>
      <ScrollArea className="flex-1">
        <div className="flex flex-col gap-1 px-2 pb-2">
          {subtitles.map((entry, i) => {
            const isActive = i === activeIndex
            return (
              <div
                key={i}
                ref={isActive ? activeRef : null}
                role="button"
                tabIndex={0}
                onClick={() => onSeek(parseSubtitleTime(entry.start))}
                onKeyDown={(e) => e.key === 'Enter' && onSeek(parseSubtitleTime(entry.start))}
                className={cn(
                  'cursor-pointer rounded p-2 text-sm transition-colors hover:bg-accent',
                  isActive && 'bg-primary/20 border-l-2 border-primary pl-2'
                )}
              >
                <p className="text-xs text-muted-foreground mb-0.5">
                  {formatTime(parseSubtitleTime(entry.start))}
                </p>
                <p
                  className={cn('leading-snug', isActive && 'text-foreground font-medium')}
                  style={{ fontFamily: '"Noto Sans JP", sans-serif' }}
                >
                  {entry.kanji}
                </p>
                <p
                  className="text-muted-foreground text-xs"
                  style={{ fontFamily: '"Noto Sans JP", sans-serif' }}
                >
                  {entry.hiragana}
                </p>
              </div>
            )
          })}
        </div>
      </ScrollArea>
    </div>
  )
}

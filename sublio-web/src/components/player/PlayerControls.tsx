import { Maximize, Minimize, Pause, Play, Volume2, VolumeX } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Slider } from '@/components/ui/slider'
import { cn, formatTime } from '@/lib/utils'

interface PlayerControlsProps {
  currentTime: number
  duration: number
  playing: boolean
  volume: number
  buffered: number
  onSeek: (t: number) => void
  onVolumeChange: (v: number) => void
  onTogglePlay: () => void
  onToggleFullscreen: () => void
  visible: boolean
}

export function PlayerControls({
  currentTime,
  duration,
  playing,
  volume,
  buffered,
  onSeek,
  onVolumeChange,
  onTogglePlay,
  onToggleFullscreen,
  visible,
}: PlayerControlsProps) {
  const isFullscreen = !!document.fullscreenElement

  return (
    <div
      className={cn(
        'absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/80 to-transparent px-4 pb-3 pt-8 transition-opacity duration-300',
        visible ? 'opacity-100' : 'opacity-0 pointer-events-none'
      )}
    >
      {/* Timeline with buffered indicator */}
      <div className="relative mb-3">
        {/* Buffered track */}
        <div
          className="absolute top-1/2 left-0 h-2 -translate-y-1/2 rounded-full bg-white/30 pointer-events-none"
          style={{ width: `${buffered}%` }}
        />
        <Slider
          aria-label="Seek"
          min={0}
          max={duration || 1}
          step={0.1}
          value={[currentTime]}
          onValueChange={([val]) => onSeek(val)}
          className="w-full"
        />
      </div>

      <div className="flex items-center gap-3">
        {/* Play/Pause */}
        <Button
          variant="ghost"
          size="icon"
          aria-label={playing ? 'Pause' : 'Play'}
          className="text-white hover:text-white hover:bg-white/20"
          onClick={onTogglePlay}
        >
          {playing ? <Pause className="h-5 w-5" /> : <Play className="h-5 w-5" />}
        </Button>

        {/* Time */}
        <span className="text-white text-sm tabular-nums">
          {formatTime(currentTime)} / {formatTime(duration)}
        </span>

        <div className="flex-1" />

        {/* Volume */}
        <Button
          variant="ghost"
          size="icon"
          aria-label={volume === 0 ? 'Unmute' : 'Mute'}
          className="text-white hover:text-white hover:bg-white/20"
          onClick={() => onVolumeChange(volume > 0 ? 0 : 1)}
        >
          {volume === 0 ? <VolumeX className="h-5 w-5" /> : <Volume2 className="h-5 w-5" />}
        </Button>
        <Slider
          aria-label="Volume"
          min={0}
          max={1}
          step={0.02}
          value={[volume]}
          onValueChange={([val]) => onVolumeChange(val)}
          className="hidden w-24 sm:block"
        />

        {/* Fullscreen */}
        <Button
          variant="ghost"
          size="icon"
          aria-label={isFullscreen ? 'Exit fullscreen' : 'Enter fullscreen'}
          className="text-white hover:text-white hover:bg-white/20"
          onClick={onToggleFullscreen}
        >
          {isFullscreen ? <Minimize className="h-5 w-5" /> : <Maximize className="h-5 w-5" />}
        </Button>
      </div>
    </div>
  )
}

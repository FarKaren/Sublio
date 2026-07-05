import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react'
import { useVideoPlayer } from '@/hooks/useVideoPlayer'
import type { SubtitleEntry } from '@/types'
import { PlayerControls } from './PlayerControls'
import { SubtitleOverlay } from './SubtitleOverlay'

export interface VideoPlayerHandle {
  seek: (t: number) => void
}

interface VideoPlayerProps {
  src: string
  subtitles: SubtitleEntry[]
  onTimeUpdate?: (t: number) => void
}

export const VideoPlayer = forwardRef<VideoPlayerHandle, VideoPlayerProps>(
  ({ src, subtitles, onTimeUpdate }, ref) => {
    const videoRef = useRef<HTMLVideoElement>(null)
    const {
      currentTime,
      duration,
      playing,
      volume,
      buffered,
      play,
      pause,
      seek,
      setVolume,
      toggleFullscreen,
    } = useVideoPlayer(videoRef)

    useImperativeHandle(ref, () => ({ seek }), [seek])

    useEffect(() => {
      onTimeUpdate?.(currentTime)
    }, [currentTime, onTimeUpdate])

    const [controlsVisible, setControlsVisible] = useState(true)
    const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

    const resetHideTimer = useCallback(() => {
      setControlsVisible(true)
      if (hideTimerRef.current) clearTimeout(hideTimerRef.current)
      hideTimerRef.current = setTimeout(() => setControlsVisible(false), 3000)
    }, [])

    const handleTogglePlay = () => {
      if (playing) pause()
      else play()
    }

    const handleKeyDown = (e: KeyboardEvent<HTMLVideoElement>) => {
      if (e.key === ' ' || e.key === 'k') {
        e.preventDefault()
        handleTogglePlay()
      } else if (e.key === 'ArrowRight') {
        e.preventDefault()
        seek(Math.min(currentTime + 5, duration))
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault()
        seek(Math.max(currentTime - 5, 0))
      }
    }

    return (
      <div
        className="relative w-full bg-black rounded-lg overflow-hidden"
        onMouseMove={resetHideTimer}
        onMouseEnter={resetHideTimer}
        onMouseLeave={() => setControlsVisible(false)}
      >
        {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
        <video
          ref={videoRef}
          src={src}
          preload="metadata"
          aria-label="Video player"
          tabIndex={0}
          className="w-full block focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          onClick={handleTogglePlay}
          onKeyDown={handleKeyDown}
        />

        <SubtitleOverlay currentTime={currentTime} subtitles={subtitles} />

        <PlayerControls
          currentTime={currentTime}
          duration={duration}
          playing={playing}
          volume={volume}
          buffered={buffered}
          onSeek={seek}
          onVolumeChange={setVolume}
          onTogglePlay={handleTogglePlay}
          onToggleFullscreen={toggleFullscreen}
          visible={controlsVisible}
        />
      </div>
    )
  }
)
VideoPlayer.displayName = 'VideoPlayer'

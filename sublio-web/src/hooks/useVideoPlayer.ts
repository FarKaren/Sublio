import { useState, useEffect, type RefObject } from 'react'

export function useVideoPlayer(videoRef: RefObject<HTMLVideoElement>) {
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [volume, setVolumeState] = useState(1)
  const [buffered, setBuffered] = useState(0)

  useEffect(() => {
    const el = videoRef.current
    if (!el) return

    const onTimeUpdate = () => setCurrentTime(el.currentTime)
    const onDurationChange = () => setDuration(el.duration)
    const onPlay = () => setPlaying(true)
    const onPause = () => setPlaying(false)
    const onVolumeChange = () => setVolumeState(el.volume)
    const onProgress = () => {
      if (el.buffered.length > 0 && el.duration > 0) {
        setBuffered((el.buffered.end(el.buffered.length - 1) / el.duration) * 100)
      }
    }

    el.addEventListener('timeupdate', onTimeUpdate)
    el.addEventListener('durationchange', onDurationChange)
    el.addEventListener('play', onPlay)
    el.addEventListener('pause', onPause)
    el.addEventListener('volumechange', onVolumeChange)
    el.addEventListener('progress', onProgress)

    return () => {
      el.removeEventListener('timeupdate', onTimeUpdate)
      el.removeEventListener('durationchange', onDurationChange)
      el.removeEventListener('play', onPlay)
      el.removeEventListener('pause', onPause)
      el.removeEventListener('volumechange', onVolumeChange)
      el.removeEventListener('progress', onProgress)
    }
  }, [videoRef])

  const play = () => {
    videoRef.current?.play().catch(() => {})
  }

  const pause = () => {
    videoRef.current?.pause()
  }

  const seek = (seconds: number) => {
    if (videoRef.current) videoRef.current.currentTime = seconds
  }

  const setVolume = (v: number) => {
    if (videoRef.current) videoRef.current.volume = v
  }

  const toggleFullscreen = () => {
    const el = videoRef.current
    if (!el) return

    const isFullscreen =
      document.fullscreenElement ||
      (document as Document & { webkitFullscreenElement: Element | null }).webkitFullscreenElement

    if (!isFullscreen) {
      if (el.requestFullscreen) {
        el.requestFullscreen().catch(() => {})
      } else {
        ;(
          el as HTMLVideoElement & { webkitRequestFullscreen?: () => void }
        ).webkitRequestFullscreen?.()
      }
    } else {
      if (document.exitFullscreen) {
        document.exitFullscreen().catch(() => {})
      } else {
        ;(document as Document & { webkitExitFullscreen?: () => void }).webkitExitFullscreen?.()
      }
    }
  }

  return {
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
  }
}

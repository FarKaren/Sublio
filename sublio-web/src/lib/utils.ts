import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

// "00:01:23,456" → seconds
export function parseSubtitleTime(srtTime: string): number {
  const [hms, ms] = srtTime.split(',')
  const [h, m, s] = hms.split(':').map(Number)
  return h * 3600 + m * 60 + s + Number(ms) / 1000
}

export function formatTime(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

export interface User {
  id: string
  email: string
}

export interface VideoInfo {
  folderId: string
  videoName: string
  createdAt: string
}

export type JobStatus = 'CREATED' | 'QUEUED' | 'TRANSCRIBING' | 'PROCESSING' | 'DONE' | 'ERROR'

export interface Job {
  id: string
  status: JobStatus
  message: string
  result?: { videoUrl: string; subtitleId: string }
}

export interface SubtitleEntry {
  start: string // "00:01:23,456"
  end: string
  kanji: string
  hiragana: string
}

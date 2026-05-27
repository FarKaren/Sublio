import type { SubtitleEntry } from '@/types'
import { instance } from '@/services/api.ts'

export const subtitleService = {
  getSubtitles: async (subtitleId: string): Promise<SubtitleEntry[]> => {
    const response = await instance.get(`/subtitles/${subtitleId}`)
    return response.data
  },
}

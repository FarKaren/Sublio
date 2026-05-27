import type { VideoInfo } from '@/types'
import { instance } from '@/services/api.ts'

export const mediaService = {
  getVideoList: async (): Promise<VideoInfo[]> => {
    const response = await instance.get('/video/list')
    return response.data
  },

  upload: async (
    file: File,
    jobId: string,
    onProgress: (percent: number) => void
  ): Promise<void> => {
    const formData = new FormData()
    formData.append('file', file)

    await instance.post(`/upload?jobId=${jobId}`, formData, {
      timeout: 0,
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (progressEvent) => {
        if (progressEvent.total) {
          const percent = Math.round((progressEvent.loaded * 100) / progressEvent.total)
          onProgress(percent)
        }
      },
    })
  },

  deleteVideo: async (folderId: string): Promise<void> => {
    await instance.delete(`/video/${folderId}`)
  },

  streamUrl: (folderId: string, fileName: string): string => {
    return `${import.meta.env.VITE_API_BASE_URL}/video/${folderId}/${fileName}`
  },
}

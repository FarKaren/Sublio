import { instance } from '@/services/api.ts'
import type { Job } from '@/types'

export const jobService = {
  createJob: async (): Promise<{ jobId: string }> => {
    const response = await instance.post('/jobs')
    return response.data
  },

  getJob: async (jobId: string): Promise<Job> => {
    const response = await instance.get(`/jobs/${jobId}`)
    return response.data
  },

  getSseToken: async (jobId: string): Promise<{ sseToken: string }> => {
    const response = await instance.post(`/jobs/${jobId}/sse-token`)
    return response.data
  },
}

import { useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { mediaService } from '@/services/mediaService'
import VideoGrid from '@/components/library/VideoGrid'
import { Button } from '@/components/ui/button'

export default function LibraryPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const {
    data: videos,
    isLoading,
    isError,
  } = useQuery({
    queryKey: ['videos'],
    queryFn: mediaService.getVideoList,
  })

  useEffect(() => {
    if (isError) toast.error('Failed to load videos')
  }, [isError])

  const deleteMutation = useMutation({
    mutationFn: mediaService.deleteVideo,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['videos'] }),
    onError: () => toast.error('Failed to delete video'),
  })

  const count = videos?.length ?? 0

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">
          Library{!isLoading && ` (${count} video${count === 1 ? '' : 's'})`}
        </h1>
        <Button onClick={() => navigate('/upload')}>+ Upload</Button>
      </div>

      <VideoGrid videos={videos ?? []} onDelete={deleteMutation.mutate} isLoading={isLoading} />
    </div>
  )
}

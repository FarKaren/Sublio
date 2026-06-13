import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { useAuth } from '@/hooks/useAuth'
import { mediaService } from '@/services/mediaService'
import VideoGrid from '@/components/library/VideoGrid'
import { Button } from '@/components/ui/button'

function GuestHero() {
  return (
    <div className="flex flex-col items-center justify-center gap-6 py-24 text-center">
      <h1 className="bg-gradient-to-r from-primary to-accent bg-clip-text text-5xl font-bold text-transparent">
        Anime Subtitles, Instantly
      </h1>
      <p className="max-w-md text-xl text-muted-foreground">
        Upload any anime episode and get perfectly synchronized Japanese subtitles with kanji and
        hiragana.
      </p>
      <Button size="lg" asChild>
        <Link to="/login">Get Started</Link>
      </Button>
    </div>
  )
}

function AuthenticatedView() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const { data: videos, isLoading } = useQuery({
    queryKey: ['videos'],
    queryFn: mediaService.getVideoList,
  })

  const deleteMutation = useMutation({
    mutationFn: mediaService.deleteVideo,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['videos'] }),
    onError: () => toast.error('Failed to delete video'),
  })

  const allVideos = videos ?? []
  const recentVideos = allVideos.slice(0, 3)
  const hasVideos = allVideos.length > 0

  return (
    <div className="flex flex-col gap-10">
      <div className="flex flex-col gap-4">
        <h1 className="bg-gradient-to-r from-primary to-accent bg-clip-text text-3xl font-bold text-transparent">
          Your Anime Library
        </h1>
        <div>
          <Button onClick={() => navigate('/upload')}>Upload Video</Button>
        </div>
      </div>

      <div className="flex flex-col gap-4">
        <div className="flex items-center justify-between">
          <h2 className="text-xl font-semibold">Recent Videos</h2>
          {hasVideos && (
            <Link
              to="/library"
              className="text-sm text-muted-foreground transition-colors hover:text-foreground"
            >
              See all →
            </Link>
          )}
        </div>

        {!isLoading && !hasVideos ? (
          <div className="flex flex-col items-center gap-4 py-20 text-center">
            <p className="text-muted-foreground">Upload your first anime episode!</p>
            <Button onClick={() => navigate('/upload')}>Upload Video</Button>
          </div>
        ) : (
          <VideoGrid videos={recentVideos} onDelete={deleteMutation.mutate} isLoading={isLoading} />
        )}
      </div>
    </div>
  )
}

export default function HomePage() {
  const { isAuthenticated } = useAuth()

  return isAuthenticated ? <AuthenticatedView /> : <GuestHero />
}

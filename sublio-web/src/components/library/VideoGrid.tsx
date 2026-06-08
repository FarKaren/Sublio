import { type FC } from 'react'
import { useNavigate } from 'react-router-dom'
import type { VideoInfo } from '@/types'
import VideoCard from '@/components/library/VideoCard.tsx'
import { Card, CardContent, CardFooter, CardHeader } from '@/components/ui/card.tsx'
import { Skeleton } from '@/components/ui/skeleton.tsx'
import { Button } from '@/components/ui/button.tsx'

interface VideoGridProps {
  videos: VideoInfo[]
  onDelete: (folderId: string) => void
  isLoading?: boolean
}

const CardSkeleton = () => (
  <Card className="flex flex-col">
    <CardHeader className="p-0">
      <Skeleton className="h-36 rounded-b-none rounded-t-lg" />
    </CardHeader>
    <CardContent className="flex flex-col gap-2 p-4">
      <Skeleton className="h-4 w-3/4" />
      <Skeleton className="h-3 w-1/2" />
    </CardContent>
    <CardFooter className="gap-2 p-4 pt-0">
      <Skeleton className="h-10 flex-1" />
      <Skeleton className="h-10 flex-1" />
    </CardFooter>
  </Card>
)

const VideoGrid: FC<VideoGridProps> = ({ videos, onDelete, isLoading = false }) => {
  const navigate = useNavigate()

  if (isLoading) {
    return (
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {Array.from({ length: 6 }).map((_, i) => (
          <CardSkeleton key={i} />
        ))}
      </div>
    )
  }

  if (videos.length === 0) {
    return (
      <div className="flex flex-col items-center gap-4 py-20 text-center">
        <p className="text-muted-foreground">No videos yet. Upload your first one!</p>
        <Button onClick={() => navigate('/upload')}>Upload video</Button>
      </div>
    )
  }

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {videos.map((video) => (
        <VideoCard key={video.folderId} video={video} onDelete={onDelete} />
      ))}
    </div>
  )
}

export default VideoGrid

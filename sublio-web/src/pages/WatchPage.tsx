import { useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { VideoPlayer, type VideoPlayerHandle } from '@/components/player/VideoPlayer'
import { SubtitlePanel } from '@/components/player/SubtitlePanel'
import { Button } from '@/components/ui/button'
import { mediaService } from '@/services/mediaService'
import { subtitleService } from '@/services/subtitleService'

export default function WatchPage() {
  const { videoId } = useParams<{ videoId: string }>()
  const navigate = useNavigate()
  const playerRef = useRef<VideoPlayerHandle>(null)
  const [currentTime, setCurrentTime] = useState(0)

  const { data: subtitles = [], isLoading: subtitlesLoading } = useQuery({
    queryKey: ['subtitles', videoId],
    queryFn: () => subtitleService.getSubtitles(videoId!),
    enabled: !!videoId,
  })

  const src = mediaService.streamUrl(videoId!, 'video.mp4')

  return (
    <div className="container mx-auto px-4 py-6">
      <div className="mb-4">
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>
          <ArrowLeft className="h-4 w-4 mr-1" />
          Back
        </Button>
      </div>

      <div className="flex flex-col lg:flex-row gap-4 items-start">
        <div className="flex-1 min-w-0">
          <VideoPlayer
            ref={playerRef}
            src={src}
            subtitles={subtitles}
            onTimeUpdate={setCurrentTime}
          />
        </div>

        <div className="w-full lg:w-80 h-64 lg:h-auto lg:self-stretch border border-border rounded-lg overflow-hidden">
          <SubtitlePanel
            subtitles={subtitles}
            currentTime={currentTime}
            onSeek={(t) => playerRef.current?.seek(t)}
            isLoading={subtitlesLoading}
          />
        </div>
      </div>
    </div>
  )
}

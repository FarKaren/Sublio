import { type FC } from 'react'
import { useNavigate } from 'react-router-dom'
import { Video } from 'lucide-react'
import type { VideoInfo } from '@/types'
import { Card, CardContent, CardFooter, CardHeader } from '@/components/ui/card.tsx'
import { Button, buttonVariants } from '@/components/ui/button.tsx'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog.tsx'

const formatDate = (iso: string) =>
  new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(iso)
  )

interface VideoCardProps {
  video: VideoInfo
  onDelete: (folderId: string) => void
}

const VideoCard: FC<VideoCardProps> = ({ video, onDelete }) => {
  const navigate = useNavigate()

  return (
    <Card className="flex flex-col">
      <CardHeader className="p-0">
        <div className="flex h-36 items-center justify-center rounded-t-lg bg-muted">
          <Video className="h-10 w-10 text-muted-foreground" />
        </div>
      </CardHeader>
      <CardContent className="flex flex-1 flex-col gap-1 p-4">
        <p className="truncate font-medium">{video.videoName}</p>
        <p className="text-xs text-muted-foreground">{formatDate(video.createdAt)}</p>
      </CardContent>
      <CardFooter className="gap-2 p-4 pt-0">
        <Button className="flex-1" onClick={() => navigate(`/watch/${video.folderId}`)}>
          Watch
        </Button>
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button variant="destructive" className="flex-1">
              Delete
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Delete video and subtitles?</AlertDialogTitle>
              <AlertDialogDescription>This action cannot be undone.</AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction
                className={buttonVariants({ variant: 'destructive' })}
                onClick={() => onDelete(video.folderId)}
              >
                Delete
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </CardFooter>
    </Card>
  )
}

export default VideoCard

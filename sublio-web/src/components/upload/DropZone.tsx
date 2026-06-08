import { type FC } from 'react'
import { useDropzone } from 'react-dropzone'
import { cn } from '@/lib/utils'

interface DropZoneProps {
  onFileSelect: (file: File) => void
}

const DropZone: FC<DropZoneProps> = ({ onFileSelect }) => {
  const { getRootProps, getInputProps, isDragActive, fileRejections } = useDropzone({
    accept: { 'video/mp4': ['.mp4'], 'video/x-matroska': ['.mkv'], 'video/avi': ['.avi'] },
    maxSize: 2 * 1024 * 1024 * 1024,
    multiple: false,
    onDrop: (acceptedFiles) => {
      if (acceptedFiles[0]) onFileSelect(acceptedFiles[0])
    },
  })

  const isRejected = fileRejections.length > 0
  const rejectionMessage = fileRejections[0]?.errors[0]?.message

  return (
    <div className="flex flex-col items-center gap-2">
      <div
        {...getRootProps()}
        className={cn(
          'flex h-48 w-full cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed transition-all',
          isDragActive && 'scale-105 border-primary text-primary',
          isRejected && 'border-destructive text-destructive',
          !isDragActive && !isRejected && 'border-muted-foreground text-muted-foreground'
        )}
      >
        <input {...getInputProps()} />
        <p className="text-sm font-medium">
          {isDragActive ? 'Drop to upload' : 'Click or drag a video file here'}
        </p>
        <p className="mt-1 text-xs opacity-60">MP4, MKV, AVI — up to 2 GB</p>
      </div>
      {isRejected && (
        <p className="text-sm text-destructive">{rejectionMessage ?? 'File rejected'}</p>
      )}
    </div>
  )
}

export default DropZone

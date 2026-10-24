package subtitle.controller

import com.sublio.subtitleservice.api.SubtitlesApi
import com.sublio.subtitleservice.model.ProcessRequest
import com.sublio.subtitleservice.model.ProcessResponse
import com.sublio.subtitleservice.model.SubtitleEntry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import subtitle.service.SubtitlesService
import java.util.UUID

@RestController
class SublioApiImpl(
    private val subtitlesService: SubtitlesService,
) : SubtitlesApi {
    override fun deleteSubtitle(id: UUID): ResponseEntity<Unit> {
        subtitlesService.deleteSubtitle(id)
        return ResponseEntity.noContent().build()
    }

    override fun getSubtitleEntries(id: UUID): ResponseEntity<List<SubtitleEntry>> {
        val subtitles = subtitlesService.getSubtitleEntries(id)
        return ResponseEntity.ok(subtitles)
    }

    override fun processSubtitles(processRequest: ProcessRequest): ResponseEntity<ProcessResponse> {
        val subtitleId = subtitlesService.processSrt(processRequest.jobId, processRequest.srtPath)
        return ResponseEntity.status(HttpStatus.CREATED).body(ProcessResponse(subtitleId))
    }
}

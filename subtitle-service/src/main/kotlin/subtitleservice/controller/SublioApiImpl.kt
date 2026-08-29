package subtitleservice.controller

import com.sublio.subtitleservice.api.SubtitlesApi
import com.sublio.subtitleservice.model.ProcessRequest
import com.sublio.subtitleservice.model.ProcessResponse
import com.sublio.subtitleservice.model.SubtitleEntry
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class SublioApiImpl(
    //private val subtitlesApiService: SubtitlesApiService
) : SubtitlesApi {

    override fun deleteSubtitle(id: UUID): ResponseEntity<Unit> {
        return TODO("Provide the return value")
    }

    override fun getSubtitleEntries(id: UUID): ResponseEntity<List<SubtitleEntry>> {
        return TODO()
    }

    override fun processSubtitles(processRequest: ProcessRequest): ResponseEntity<ProcessResponse> {
        return TODO()
    }

}
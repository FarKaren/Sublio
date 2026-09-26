package subtitle.service


import io.micrometer.tracing.Span
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import subtitle.configs.AppProperties
import subtitle.models.Subtitle
import subtitle.models.SubtitleEntry
import subtitle.repository.SubtitleEntryRepository
import subtitle.repository.SubtitleRepository
import subtitle.service.KuromojService.toHiragana
import io.micrometer.tracing.Tracer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

@Service
class SubtitlesService(
    private val subtitleEntryRepository: SubtitleEntryRepository,
    private val subtitleRepository: SubtitleRepository,
    private val appProperties: AppProperties,
    private val tracer: Tracer
) {

    fun processSrt(jobId: UUID, strPath: String): UUID {
        val span = tracer.nextSpan().name("processSrt").start()


        try {
            tracer.withSpan(span).use {
                val safePath = resolveExisting(strPath)

                val fileContent = Files.readString(safePath)
                val subtitleEntries = SrtParser.parse(fileContent)

                val subtitle = Subtitle().apply { this.jobId = jobId }
                val savedSubtitle = subtitleRepository.save(subtitle)

                val subtitlesWithHiragana = subtitleEntries.map {
                    val hiragana = toHiragana(it.kanjiText)
                    SubtitleEntry().apply {
                        this.subtitle = savedSubtitle
                        seqNum = it.seqNum
                        startTime = it.start
                        endTime = it.end
                        kanjiText = it.kanjiText
                        hiraganaText = hiragana
                    }
                }

                subtitleEntryRepository.saveAll(subtitlesWithHiragana)

                return savedSubtitle.id!!
            }

        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Throwable) {
            throw RuntimeException(e.message, e)
        }
        finally {
            span.end()
        }

    }

    private fun resolveExisting(strPath: String): Path {
        val baseDir = Path.of(appProperties.processedFileDir)
        val candidate = resolve(baseDir, strPath)

        val real = try {
            candidate.toRealPath()
        } catch (e: IOException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")
        }

        val realBase = baseDir.toRealPath()
        if (!real.startsWith(realBase)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path")
        }

        return real
    }

    private fun resolve(baseDir: Path, userInput: String): Path {
        val base = baseDir.toAbsolutePath().normalize()

        val candidate = base.resolve(userInput).normalize()

        if (!candidate.startsWith(base)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path")
        }

        return candidate
    }
}
package subtitle.service

import io.micrometer.tracing.Tracer
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import subtitle.configs.AppProperties
import subtitle.exception.InvalidPathException
import subtitle.exception.InvalidSrtFormatException
import subtitle.exception.SrtFileNotFoundException
import subtitle.exception.SrtParseException
import subtitle.models.Subtitle
import subtitle.models.SubtitleEntry
import subtitle.repository.SubtitleEntryRepository
import subtitle.repository.SubtitleRepository
import subtitle.service.KuromojService.toHiragana
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Service
class SubtitlesService(
    private val subtitleEntryRepository: SubtitleEntryRepository,
    private val subtitleRepository: SubtitleRepository,
    private val appProperties: AppProperties,
    private val tracer: Tracer,
    private val transactionTemplate: TransactionTemplate,
) {
    fun processSrt(
        jobId: UUID,
        strPath: String,
    ): UUID {
        val span = tracer.nextSpan().name("processSrt").start()

        try {
            tracer.withSpan(span).use {
                // 1. Prepare outside the transaction: file IO, parsing, Kuromoji
                val parsedEntries = parseSrt(resolveExisting(strPath))
                val hiraganaTexts = parsedEntries.map { toHiragana(it.kanjiText) }

                // 2. Persist atomically: subtitle with all entries, or nothing
                return transactionTemplate.execute {
                    val savedSubtitle = subtitleRepository.save(Subtitle().apply { this.jobId = jobId })

                    val entries =
                        parsedEntries.zip(hiraganaTexts) { parsed, hiragana ->
                            SubtitleEntry().apply {
                                subtitle = savedSubtitle
                                seqNum = parsed.seqNum
                                startTime = parsed.start
                                endTime = parsed.end
                                kanjiText = parsed.kanjiText
                                hiraganaText = hiragana
                            }
                        }
                    subtitleEntryRepository.saveAll(entries)

                    savedSubtitle.id!!
                }!!
            }
        } finally {
            span.end()
        }
    }

    // Maps file/parse problems to INVALID_SRT_FORMAT; raw file content stays in `cause`, never in the response
    private fun parseSrt(path: Path): List<SrtEntry> =
        try {
            SrtParser.parse(Files.readString(path))
        } catch (e: SrtParseException) {
            throw InvalidSrtFormatException(e)
        } catch (e: CharacterCodingException) {
            throw InvalidSrtFormatException(e)
        }

    private fun resolveExisting(strPath: String): Path {
        val baseDir = Path.of(appProperties.processedFileDir)
        val candidate = resolve(baseDir, strPath)

        val real =
            try {
                candidate.toRealPath()
            } catch (e: IOException) {
                throw SrtFileNotFoundException(e)
            }

        if (!real.startsWith(baseDir.toRealPath())) {
            throw InvalidPathException()
        }

        return real
    }

    private fun resolve(
        baseDir: Path,
        userInput: String,
    ): Path {
        val base = baseDir.toAbsolutePath().normalize()
        val candidate = base.resolve(userInput).normalize()

        if (!candidate.startsWith(base)) {
            throw InvalidPathException()
        }

        return candidate
    }
}

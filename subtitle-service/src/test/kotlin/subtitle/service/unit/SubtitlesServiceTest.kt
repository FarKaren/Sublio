package subtitle.service.unit

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import subtitle.configs.AppProperties
import subtitle.models.Subtitle
import subtitle.models.SubtitleEntry
import subtitle.repository.SubtitleEntryRepository
import subtitle.repository.SubtitleRepository
import subtitle.service.SubtitlesService
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText

class SubtitlesServiceTest {

    private val subtitleRepository = mock(SubtitleRepository::class.java)
    private val subtitleEntryRepository = mock(SubtitleEntryRepository::class.java)

    private lateinit var processedDir: Path
    private lateinit var service: SubtitlesService

    @BeforeEach
    fun setUp() {
        processedDir = Files.createTempDirectory("sublio-processed-test")

        val appProperties = AppProperties(processedFileDir = processedDir.toString())
        service = SubtitlesService(subtitleEntryRepository, subtitleRepository, appProperties, noopTracer())

        `when`(subtitleRepository.save(any())).thenAnswer { invocation ->
            (invocation.arguments[0] as Subtitle).apply { id = UUID.randomUUID() }
        }
    }

    @AfterEach
    fun tearDown() {
        Files.walk(processedDir).sorted(Comparator.reverseOrder()).forEach { it.deleteExisting() }
    }

    private fun noopTracer(): Tracer {
        val span = mock(Span::class.java)
        `when`(span.name(any())).thenReturn(span)
        `when`(span.start()).thenReturn(span)

        val spanInScope = mock(Tracer.SpanInScope::class.java)

        val tracer = mock(Tracer::class.java)
        `when`(tracer.nextSpan()).thenReturn(span)
        `when`(tracer.withSpan(span)).thenReturn(spanInScope)
        return tracer
    }

    @Test
    fun `parses the srt file, converts to hiragana and persists subtitle plus entries`() {
        val srtFile = processedDir.resolve("kanji.srt")
        srtFile.writeText(
            """
            1
            00:00:00,000 --> 00:00:02,000
            東京に行きます
            """.trimIndent()
        )

        val jobId = UUID.randomUUID()
        val subtitleId = service.processSrt(jobId, "kanji.srt")

        assertNotNull(subtitleId)

        val subtitleCaptor = ArgumentCaptor.forClass(Subtitle::class.java)
        verify(subtitleRepository).save(subtitleCaptor.capture())
        assertEquals(jobId, subtitleCaptor.value.jobId)

        @Suppress("UNCHECKED_CAST")
        val entriesCaptor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<SubtitleEntry>>
        verify(subtitleEntryRepository).saveAll(entriesCaptor.capture())

        val savedEntries = entriesCaptor.value
        assertEquals(1, savedEntries.size)
        assertEquals("東京に行きます", savedEntries[0].kanjiText)
        assertEquals("とうきょうにいきます", savedEntries[0].hiraganaText)
        assertEquals(subtitleId, savedEntries[0].subtitle?.id)
    }

    @Test
    fun `rejects a srtPath that escapes the processed-files root with 400, without touching the repositories`() {
        val ex = assertThrows(ResponseStatusException::class.java) {
            service.processSrt(UUID.randomUUID(), "../../../etc/passwd")
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        verifyNoInteractions(subtitleRepository)
        verifyNoInteractions(subtitleEntryRepository)
    }

    @Test
    fun `returns 404 when the srtPath does not exist under the processed-files root`() {
        val ex = assertThrows(ResponseStatusException::class.java) {
            service.processSrt(UUID.randomUUID(), "missing.srt")
        }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }
}

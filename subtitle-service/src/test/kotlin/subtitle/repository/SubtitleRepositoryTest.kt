package subtitle.repository.it

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import subtitle.controller.it.AbstractSubtitleTest
import subtitle.models.Subtitle
import subtitle.models.SubtitleEntry
import subtitle.repository.SubtitleEntryRepository
import subtitle.repository.SubtitleRepository
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubtitleRepositoryTest : AbstractSubtitleTest() {

    @Autowired
    lateinit var subtitleRepository: SubtitleRepository

    @Autowired
    lateinit var subtitleEntryRepository: SubtitleEntryRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var seededJobId: UUID

    @BeforeEach
    fun seedJob() {
        seededJobId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO sublio.jobs (id, user_id, idempotency_key, status) VALUES (?, ?, ?, ?)",
            seededJobId, UUID.randomUUID(), UUID.randomUUID(), "DONE"
        )
    }

    @Test
    fun `persists a subtitle and its entries, readable back through the repositories`() {
        val subtitle = subtitleRepository.save(Subtitle().apply { this.jobId = seededJobId })
        assertNotNull(subtitle.id)
        assertNotNull(subtitle.createdAt)

        val entry = subtitleEntryRepository.save(
            SubtitleEntry().apply {
                this.subtitle = subtitle
                seqNum = 1
                startTime = "00:00:00,000"
                endTime = "00:00:02,000"
                kanjiText = "東京に行きます"
                hiraganaText = "とうきょうにいきます"
            }
        )
        assertNotNull(entry.id)

        val reloaded = subtitleEntryRepository.findById(entry.id!!).orElseThrow()
        assertEquals(subtitle.id, reloaded.subtitle?.id)
        assertEquals("とうきょうにいきます", reloaded.hiraganaText)
        assertTrue(subtitleRepository.findById(subtitle.id!!).isPresent)
    }

    @Test
    fun `rejects a subtitle row whose job_id does not exist (FK constraint)`() {
        assertThrows(DataIntegrityViolationException::class.java) {
            subtitleRepository.saveAndFlush(Subtitle().apply { this.jobId = UUID.randomUUID() })
        }
    }
}

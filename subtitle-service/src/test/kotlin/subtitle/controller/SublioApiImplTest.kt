package subtitle.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import subtitle.controller.it.AbstractSubtitleTest
import java.util.UUID
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SublioApiImplTest : AbstractSubtitleTest() {
    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @LocalServerPort
    var port: Int = 0

    private lateinit var jobId: UUID

    @BeforeEach
    fun seedJob() {
        jobId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO sublio.jobs (id, user_id, idempotency_key, status) VALUES (?, ?, ?, ?)",
            jobId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "TRANSCRIBED",
        )
    }

    @Test
    fun `POST process parses the srt file and returns 201 with a subtitleId`() {
        processedFileDir.resolve("job-$jobId.srt").writeText(
            """
            1
            00:00:00,000 --> 00:00:02,000
            東京に行きます
            """.trimIndent(),
        )

        val response =
            restTemplate.postForEntity(
                "http://localhost:$port/process",
                mapOf("jobId" to jobId, "srtPath" to "job-$jobId.srt"),
                Map::class.java,
            )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertNotNull(response.body?.get("subtitleId"))
    }

    @Test
    fun `POST process rejects a srtPath outside the shared volume with 400`() {
        val response =
            restTemplate.postForEntity(
                "http://localhost:$port/process",
                mapOf("jobId" to UUID.randomUUID(), "srtPath" to "../../../etc/passwd"),
                Map::class.java,
            )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    private fun seedSubtitleWithEntries(): UUID {
        val subtitleId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO sublio.subtitles (id, job_id) VALUES (?, ?)",
            subtitleId,
            jobId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO sublio.subtitle_entries (subtitle_id, seq_num, start_time, end_time, kanji_text, hiragana_text)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            subtitleId,
            1,
            "00:00:00,000",
            "00:00:02,000",
            "東京に行きます",
            "とうきょうにいきます",
        )
        return subtitleId
    }

    @Test
    fun `GET subtitles id returns the entries in sequence order with 200`() {
        val subtitleId = seedSubtitleWithEntries()

        val response =
            restTemplate.getForEntity(
                "http://localhost:$port/subtitles/$subtitleId",
                List::class.java,
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(1, response.body?.size)
    }

    @Test
    fun `GET subtitles id returns 404 SUBTITLE_NOT_FOUND for an unknown id`() {
        val response =
            restTemplate.getForEntity(
                "http://localhost:$port/subtitles/${UUID.randomUUID()}",
                Map::class.java,
            )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("SUBTITLE_NOT_FOUND", response.body?.get("code"))
    }

    @Test
    fun `DELETE subtitles id deletes the subtitle and its entries, returning 204`() {
        val subtitleId = seedSubtitleWithEntries()

        val response =
            restTemplate.exchange(
                "http://localhost:$port/subtitles/$subtitleId",
                org.springframework.http.HttpMethod.DELETE,
                null,
                Void::class.java,
            )

        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sublio.subtitles WHERE id = ?",
                Int::class.java,
                subtitleId,
            ),
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sublio.subtitle_entries WHERE subtitle_id = ?",
                Int::class.java,
                subtitleId,
            ),
        )
    }

    @Test
    fun `DELETE subtitles id returns 404 SUBTITLE_NOT_FOUND for an unknown id`() {
        val response =
            restTemplate.exchange(
                "http://localhost:$port/subtitles/${UUID.randomUUID()}",
                org.springframework.http.HttpMethod.DELETE,
                null,
                Map::class.java,
            )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("SUBTITLE_NOT_FOUND", response.body?.get("code"))
    }
}

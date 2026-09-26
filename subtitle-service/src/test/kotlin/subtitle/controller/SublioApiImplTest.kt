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
            jobId, UUID.randomUUID(), UUID.randomUUID(), "TRANSCRIBED"
        )
    }

    @Test
    fun `POST process parses the srt file and returns 201 with a subtitleId`() {
        processedFileDir.resolve("job-$jobId.srt").writeText(
            """
            1
            00:00:00,000 --> 00:00:02,000
            東京に行きます
            """.trimIndent()
        )

        val response = restTemplate.postForEntity(
            "http://localhost:$port/process",
            mapOf("jobId" to jobId, "srtPath" to "job-$jobId.srt"),
            Map::class.java
        )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertNotNull(response.body?.get("subtitleId"))
    }

    @Test
    fun `POST process rejects a srtPath outside the shared volume with 400`() {
        val response = restTemplate.postForEntity(
            "http://localhost:$port/process",
            mapOf("jobId" to UUID.randomUUID(), "srtPath" to "../../../etc/passwd"),
            Map::class.java
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }
}

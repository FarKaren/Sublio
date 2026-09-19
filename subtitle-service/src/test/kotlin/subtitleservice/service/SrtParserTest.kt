package subtitleservice.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SrtParserTest {

    @Test
    fun `parses well-formed SRT content into ordered entries`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,000
            今日は良い天気です

            2
            00:00:04,500 --> 00:00:06,000
            東京に行きます
        """.trimIndent()

        val entries = SrtParser.parse(srt)

        assertEquals(2, entries.size)
        assertEquals(SrtEntry(1, "00:00:01,000", "00:00:03,000", "今日は良い天気です"), entries[0])
        assertEquals(SrtEntry(2, "00:00:04,500", "00:00:06,000", "東京に行きます"), entries[1])
    }

    @Test
    fun `joins multi-line subtitle text into a single entry`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,000
            一行目
            二行目
        """.trimIndent()

        val entries = SrtParser.parse(srt)

        assertEquals(1, entries.size)
        assertEquals("一行目 二行目", entries[0].kanjiText)
    }

    @Test
    fun `throws a clear error on empty input instead of returning silently`() {
        assertFailsWith<SrtParseException> { SrtParser.parse("") }
        assertFailsWith<SrtParseException> { SrtParser.parse("   \n\n  ") }
    }

    @Test
    fun `throws a clear error when a block is missing its text`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,000
        """.trimIndent()

        assertFailsWith<SrtParseException> { SrtParser.parse(srt) }
    }

    @Test
    fun `throws a clear error on a non-numeric sequence number`() {
        val srt = """
            one
            00:00:01,000 --> 00:00:03,000
            テスト

            2
            00:00:04,000 --> 00:00:05,000
            テスト2
        """.trimIndent()

        assertFailsWith<SrtParseException> { SrtParser.parse(srt) }
    }

    @Test
    fun `throws a clear error on a malformed time range`() {
        val srt = """
            1
            00:00:01,000 - 00:00:03,000
            テスト
        """.trimIndent()

        assertFailsWith<SrtParseException> { SrtParser.parse(srt) }
    }
}

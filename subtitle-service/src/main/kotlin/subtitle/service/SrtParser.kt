package subtitle.service

import subtitle.exception.SrtParseException

object SrtParser {
    private const val MIN_BLOCK_LINES = 3

    fun parse(srtContent: String): List<SrtEntry> {
        val blocks =
            srtContent
                .split(Regex("\r?\n\r?\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        if (blocks.isEmpty()) {
            fail("No subtitle blocks found in SRT content")
        }

        return blocks.map { parseBlock(it) }
    }

    private fun parseBlock(block: String): SrtEntry {
        val lines = block.lines().filter { it.isNotBlank() }
        if (lines.size < MIN_BLOCK_LINES) {
            fail("Malformed SRT block, expected sequence number, time range and text:\n$block")
        }

        val seqNum =
            lines[0].trim().toIntOrNull()
                ?: fail("Malformed SRT block, invalid sequence number: \"${lines[0]}\"")

        val timeLine = lines[1]
        val timeParts = timeLine.split(" --> ")
        if (timeParts.size != 2) {
            fail("Malformed SRT block, invalid time range: \"$timeLine\"")
        }

        val textLine = lines.subList(2, lines.size).joinToString(" ").trim()
        if (textLine.isEmpty()) {
            fail("Malformed SRT block, missing subtitle text for entry $seqNum")
        }

        return SrtEntry(seqNum, timeParts[0].trim(), timeParts[1].trim(), textLine)
    }

    private fun fail(message: String): Nothing = throw SrtParseException(message)
}

data class SrtEntry(
    val seqNum: Int,
    val start: String,
    val end: String,
    val kanjiText: String,
)

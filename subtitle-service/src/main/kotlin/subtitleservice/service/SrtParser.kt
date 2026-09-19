package subtitleservice.service

class SrtParseException(message: String) : RuntimeException(message)

object SrtParser {
    fun parse(srtContent: String): List<SrtEntry> {
        val blocks = srtContent.split(Regex("\r?\n\r?\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (blocks.isEmpty()) {
            throw SrtParseException("No subtitle blocks found in SRT content")
        }

        return blocks.map { parseBlock(it) }
    }

    private fun parseBlock(block: String): SrtEntry {
        val lines = block.lines().filter { it.isNotBlank() }
        if (lines.size < 3) {
            throw SrtParseException("Malformed SRT block, expected sequence number, time range and text:\n$block")
        }

        val seqNum = lines[0].trim().toIntOrNull()
            ?: throw SrtParseException("Malformed SRT block, invalid sequence number: \"${lines[0]}\"")

        val timeLine = lines[1]
        val timeParts = timeLine.split(" --> ")
        if (timeParts.size != 2) {
            throw SrtParseException("Malformed SRT block, invalid time range: \"$timeLine\"")
        }

        val textLine = lines.subList(2, lines.size).joinToString(" ").trim()
        if (textLine.isEmpty()) {
            throw SrtParseException("Malformed SRT block, missing subtitle text for entry $seqNum")
        }

        return SrtEntry(seqNum, timeParts[0].trim(), timeParts[1].trim(), textLine)
    }
}

data class SrtEntry(val seqNum: Int, val start: String, val end: String, val kanjiText: String)
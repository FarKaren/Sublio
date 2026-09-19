package subtitleservice.service

import com.atilika.kuromoji.ipadic.Tokenizer

class KuromojService {
    private val tokenizer = Tokenizer()

    fun toHiragana(kanjiText: String): String =
        tokenizer.tokenize(kanjiText).joinToString("") { token ->
            val reading = token.reading
            if (reading == null || reading == "*") token.surface else katakanaToHiragana(reading)
        }

    private fun katakanaToHiragana(katakana: String): String =
        katakana.map { c -> if (c in 'ァ'..'ヶ') c - 0x60 else c }.joinToString("")
}
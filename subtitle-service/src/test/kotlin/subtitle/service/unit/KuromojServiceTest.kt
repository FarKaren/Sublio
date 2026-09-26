package subtitle.service.unit

import subtitle.service.KuromojService
import kotlin.test.Test
import kotlin.test.assertEquals

class KuromojServiceTest {


    @Test
    fun `converts a known kanji sentence to its hiragana reading`() {
        assertEquals("たべる", KuromojService.toHiragana("食べる"))
        assertEquals("とうきょうにいきます", KuromojService.toHiragana("東京に行きます"))
        assertEquals("きょうはよいてんきです", KuromojService.toHiragana("今日は良い天気です"))
    }

    @Test
    fun `falls back to the original surface for an out-of-dictionary proper noun`() {
        // "犇" (a rare surname kanji) has no reading in the IPADIC dictionary — Kuromoji
        // returns the literal string "*" for it, not null, so the fallback must check for
        // both. The rest of the sentence still converts normally.
        assertEquals("犇きさんはえんじにあです", KuromojService.toHiragana("犇木さんはエンジニアです"))
    }
}

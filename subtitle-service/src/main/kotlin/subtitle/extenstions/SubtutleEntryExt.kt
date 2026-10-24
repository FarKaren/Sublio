package subtitle.extenstions

import subtitle.models.SubtitleEntry

fun SubtitleEntry.toSubEntryDto(): com.sublio.subtitleservice.model.SubtitleEntry =
    com.sublio.subtitleservice.model.SubtitleEntry(
        start = startTime,
        end = endTime,
        kanji = kanjiText,
        hiragana = hiraganaText,
    )

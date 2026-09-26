package subtitle.models

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "subtitle_entries", schema = "sublio")
open class SubtitleEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subtitle_id")
    open var subtitle: Subtitle? = null

    @Column(name = "seq_num", nullable = false)
    open var seqNum: Int? = null

    @Column(name = "start_time", nullable = false, length = 20)
    open var startTime: String? = null

    @Column(name = "end_time", nullable = false, length = 20)
    open var endTime: String? = null

    @Column(name = "kanji_text", nullable = false, columnDefinition = "TEXT")
    open var kanjiText: String? = null

    @Column(name = "hiragana_text", nullable = false, columnDefinition = "TEXT")
    open var hiraganaText: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SubtitleEntry) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String =
        "SubtitleEntry(id=$id, subtitleId=$subtitle, seqNum=$seqNum, startTime='$startTime', endTime='$endTime')"
}
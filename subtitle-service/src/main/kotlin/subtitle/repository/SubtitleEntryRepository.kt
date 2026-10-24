package subtitle.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import subtitle.models.SubtitleEntry
import java.util.UUID

@Repository
interface SubtitleEntryRepository : JpaRepository<SubtitleEntry, Long> {
    fun findBySubtitleIdOrderBySeqNumAsc(subtitleId: UUID): List<SubtitleEntry>

    fun deleteBySubtitleId(subtitleId: UUID)
}

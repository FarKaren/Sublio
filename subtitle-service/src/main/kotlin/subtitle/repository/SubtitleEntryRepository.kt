package subtitle.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import subtitle.models.SubtitleEntry

@Repository
interface SubtitleEntryRepository : JpaRepository<SubtitleEntry, Long>
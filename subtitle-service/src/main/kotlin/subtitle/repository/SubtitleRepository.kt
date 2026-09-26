package subtitle.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import subtitle.models.Subtitle
import subtitle.models.SubtitleEntry
import java.util.UUID

@Repository
interface SubtitleRepository: JpaRepository<Subtitle, UUID> {
}
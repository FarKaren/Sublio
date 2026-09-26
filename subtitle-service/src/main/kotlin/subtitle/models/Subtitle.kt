package subtitle.models

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UuidGenerator
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "subtitles", schema = "sublio")
open class Subtitle {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    open var id: UUID? = null

    @Column(name = "job_id", nullable = false)
    open var jobId: UUID? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    open var createdAt: OffsetDateTime? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Subtitle) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String =
        "Subtitle(id=$id, jobId=$jobId, createdAt=$createdAt)"
}
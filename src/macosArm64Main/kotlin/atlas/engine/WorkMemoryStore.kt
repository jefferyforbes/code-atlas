package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.model.ActiveAnnotation
import atlas.util.Sha256
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.math.pow
import kotlin.math.round
import platform.posix.time

/**
 * Stores and retrieves agent Q&A and debugging outcomes on graph nodes with 30-day exponential decay.
 */
@OptIn(ExperimentalForeignApi::class)
class WorkMemoryStore(private val store: SqliteGraphStore) {

    companion object {
        const val HALF_LIFE_DAYS = 30.0
    }

    fun recordOutcome(
        projectId: String,
        nodeId: String,
        outcome: String,
        note: String,
        createdAtEpoch: Double? = null
    ): String {
        val ts = createdAtEpoch ?: time(null).toDouble()
        val rawId = "$projectId:$nodeId:$outcome:$note:$ts"
        val annId = Sha256.digest(rawId).take(16)

        store.insertNodeAnnotation(
            annotationId = annId,
            projectId = projectId,
            nodeId = nodeId,
            outcome = outcome,
            note = note,
            createdAtEpoch = ts
        )
        return annId
    }

    fun getActiveAnnotations(
        projectId: String,
        nodeId: String,
        nowEpoch: Double? = null,
        minWeight: Double = 0.20
    ): List<ActiveAnnotation> {
        val nowTs = nowEpoch ?: time(null).toDouble()
        val rows = store.getNodeAnnotations(projectId, nodeId)
        val active = mutableListOf<ActiveAnnotation>()

        for (row in rows) {
            val ageDays = ((nowTs - row.createdAtEpoch) / 86400.0).coerceAtLeast(0.0)
            val decayWeight = 0.5.pow(ageDays / HALF_LIFE_DAYS)
            if (decayWeight >= minWeight) {
                active.add(
                    ActiveAnnotation(
                        annotationId = row.annotationId,
                        outcome = row.outcome,
                        note = row.note,
                        ageDays = (round(ageDays * 100.0) / 100.0),
                        decayWeight = (round(decayWeight * 10000.0) / 10000.0)
                    )
                )
            }
        }
        return active
    }
}

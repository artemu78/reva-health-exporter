package dev.reva.healthexporter

import java.time.Duration
import java.time.Instant

/**
 * Resolves source revisions before an immutable export batch is created.
 *
 * Sleep revisions use a source identifier when one is stable. Xiaomi snapshots observed with
 * changing identifiers fall back only to the same origin and exact start time, with nested
 * session intervals; timestamp proximity alone is never an identity signal.
 */
object ExportRecordCanonicalizer {
    fun canonicalize(records: List<CanonicalRecord>): List<CanonicalRecord> {
        val sleepRecords = records.filterIsInstance<CanonicalSleepSessionRecord>()
        val otherRecords = records.filterNot { it is CanonicalSleepSessionRecord }

        return canonicalizeOtherRecords(otherRecords) + canonicalizeSleepRecords(sleepRecords)
    }

    private fun canonicalizeOtherRecords(records: List<CanonicalRecord>): List<CanonicalRecord> {
        val withoutExactDuplicates = records.distinct()
        val withStableIdentity = withoutExactDuplicates.filter { it.sourceRecordId() != null }
            .groupBy { Triple(it.recordType, it.metadata.origin, it.sourceRecordId()) }
            .values
            .map { candidates -> candidates.maxWith(canonicalRecordComparator) }
        val withoutStableIdentity = withoutExactDuplicates.filter { it.sourceRecordId() == null }
        return withStableIdentity + withoutStableIdentity
    }

    private fun canonicalizeSleepRecords(
        records: List<CanonicalSleepSessionRecord>,
    ): List<CanonicalSleepSessionRecord> {
        val unique = records.distinct()
        val remaining = unique.toMutableSet()
        val canonical = mutableListOf<CanonicalSleepSessionRecord>()

        while (remaining.isNotEmpty()) {
            val component = mutableSetOf(remaining.first())
            var changed: Boolean
            do {
                changed = false
                val revisions = remaining.filter { candidate ->
                    component.any { existing -> sameLogicalSleepSession(existing, candidate) }
                }
                if (component.addAll(revisions)) changed = true
            } while (changed)

            remaining.removeAll(component)
            canonical += component.maxWith(sleepSnapshotComparator)
        }

        return canonical
    }

    private fun sameLogicalSleepSession(
        first: CanonicalSleepSessionRecord,
        second: CanonicalSleepSessionRecord,
    ): Boolean {
        if (first.metadata.origin != second.metadata.origin) return false

        if (hasMatchingSourceId(first, second)) return true

        return first.startTime == second.startTime && intervalsContainOneAnother(first, second)
    }

    private fun intervalsContainOneAnother(
        first: CanonicalSleepSessionRecord,
        second: CanonicalSleepSessionRecord,
    ): Boolean =
        contains(first, second) || contains(second, first)

    private fun contains(
        outer: CanonicalSleepSessionRecord,
        inner: CanonicalSleepSessionRecord,
    ): Boolean =
        !outer.startTime.isAfter(inner.startTime) && !outer.endTime.isBefore(inner.endTime)

    private fun hasMatchingSourceId(
        first: CanonicalSleepSessionRecord,
        second: CanonicalSleepSessionRecord,
    ): Boolean =
        first.metadata.clientRecordId != null &&
            first.metadata.clientRecordId == second.metadata.clientRecordId ||
            first.metadata.recordId != null && first.metadata.recordId == second.metadata.recordId

    private val canonicalRecordComparator = compareBy<CanonicalRecord>(
        { it.endTime },
        { it.metadata.lastModifiedTime ?: Instant.MIN },
        { it.metadata.clientRecordVersion ?: Long.MIN_VALUE },
        { it.toString() },
    )

    private val sleepSnapshotComparator = compareBy<CanonicalSleepSessionRecord>(
        { it.endTime },
        { stageDurationMillis(it) },
        { it.stages.size },
        { listOfNotNull(it.title?.takeIf(String::isNotBlank), it.notes?.takeIf(String::isNotBlank)).size },
        { it.metadata.lastModifiedTime ?: Instant.MIN },
        { it.metadata.clientRecordVersion ?: Long.MIN_VALUE },
        { it.toString() },
    )

    private fun stageDurationMillis(record: CanonicalSleepSessionRecord): Long =
        record.stages.sumOf { Duration.between(it.startTime, it.endTime).toMillis() }

    private fun CanonicalRecord.sourceRecordId(): String? =
        metadata.clientRecordId ?: metadata.recordId
}

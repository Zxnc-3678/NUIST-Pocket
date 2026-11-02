package cn.nuist.pocket.data

internal data class SchoolSnapshotToken(val track: StudentTrack, val selectionRevision: Long, val dataRevision: Long)

/** A type can change away and back while IO runs; compare the data revision as well. */
internal class SchoolSnapshotGuard {
    private val lock = Any()
    private var selectionRevision = 0L
    private val dataRevisions = StudentTrack.entries.associateWith { 0L }.toMutableMap()

    fun capture(currentTrack: () -> StudentTrack): SchoolSnapshotToken = synchronized(lock) {
        val track = currentTrack()
        SchoolSnapshotToken(track, selectionRevision, dataRevisions.getValue(track))
    }

    fun mutate(track: StudentTrack? = null, action: () -> Unit) = synchronized(lock) {
        try { action() } finally {
            if (track == null) selectionRevision++ else dataRevisions[track] = dataRevisions.getValue(track) + 1
        }
    }

    fun publish(token: SchoolSnapshotToken, currentTrack: () -> StudentTrack, action: () -> Unit): Boolean = synchronized(lock) {
        if (token.selectionRevision != selectionRevision || token.track != currentTrack() ||
            token.dataRevision != dataRevisions.getValue(token.track)) false else { action(); true }
    }
}

internal val schoolSnapshots = SchoolSnapshotGuard()

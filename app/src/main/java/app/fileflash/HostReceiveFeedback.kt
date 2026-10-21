package app.fileflash

/** Process-local receiving feedback. A null total never represents a percentage. */
data class HostReceiveFeedback(
    val phase: String,
    val name: String,
    val fileCount: Int,
    val receivedBytes: Long,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long,
    val active: Boolean
)

/** Observes successful snapshots only; recovered upload records are not evidence of live traffic. */
internal class HostReceiveFeedbackTracker(
    private val waitingAfterMs: Long = 8_000L,
    private val terminalForMs: Long = 4_000L
) {
    private data class Observation(val offset: Long, val status: String, val at: Long)
    private data class Entry(
        val id: String, val name: String, val received: Long, val total: Long?,
        val status: String, val lastActivity: Long, val speed: Long, val terminalAt: Long?
    )

    private var generation = Long.MIN_VALUE
    private var started = false
    private val observed = linkedMapOf<String, Observation>()
    private val knownFiles = linkedSetOf<String>()
    private val entries = linkedMapOf<String, Entry>()

    @Synchronized fun start(generation: Long, baseline: ServerState, nowMs: Long) {
        this.generation = generation
        started = true
        observed.clear(); knownFiles.clear(); entries.clear()
        baseline.uploads.take(MAX_TRACKED).forEach { observed[it.id] = Observation(it.offset, it.status, nowMs) }
        baseline.files.filter { it.received }.take(MAX_TRACKED).forEach { knownFiles.add(it.id) }
    }

    @Synchronized fun update(generation: Long, state: ServerState, nowMs: Long): HostReceiveFeedback? {
        if (!started || generation != this.generation) return null
        expire(nowMs)
        val uploads = state.uploads.take(MAX_TRACKED).filter { it.id.isNotEmpty() }
        val currentIds = uploads.mapTo(hashSetOf()) { it.id }
        val files = state.files.asSequence().filter { it.received }.take(MAX_TRACKED).associateBy { it.id }
        if (entries.values.none { it.terminalAt == null }) {
            val newUploadRound = uploads.any { upload ->
                val previous = observed[upload.id]
                val newArrival = previous == null && upload.id !in knownFiles
                val changed = previous != null && (previous.offset != upload.offset || previous.status != upload.status)
                newArrival || (changed && upload.status !in TERMINAL)
            }
            val newFileRound = files.keys.any { it !in knownFiles && it !in currentIds && it !in entries }
            // A retry or a new arrival replaces a finished batch; concurrent members still share its outcome.
            if (newUploadRound || newFileRound) entries.clear()
        }
        uploads.asReversed().forEach { upload ->
            val previous = observed[upload.id]
            val existing = entries[upload.id]
            val changed = previous != null && (previous.offset != upload.offset || previous.status != upload.status)
            val newArrival = previous == null && upload.id !in knownFiles
            if (existing != null || changed || newArrival) {
                val complete = upload.status in COMPLETE || (upload.status == "cancelled" && upload.id in files)
                val terminal = complete || upload.status in TERMINAL
                val received = if (complete) maxOf(upload.size, upload.offset, 0) else upload.offset.coerceAtLeast(0)
                val total = if (upload.lengthKnown || complete) maxOf(upload.size, received, 0) else null
                val status = if (complete) "complete" else upload.status
                val activity = if (existing == null || changed) nowMs else existing.lastActivity
                val terminalAt = if (terminal) existing?.terminalAt ?: nowMs else null
                val offset = upload.offset.coerceAtLeast(0)
                val priorOffset = previous?.offset?.coerceAtLeast(0) ?: offset
                val delta = if (offset >= priorOffset) offset - priorOffset else 0
                val speed = if (!terminal && previous != null) rate(delta, elapsed(nowMs, previous.at)) else 0
                putEntry(Entry(upload.id, upload.name, received, total, status, activity, speed, terminalAt))
            }
            observed.remove(upload.id)
            observed[upload.id] = Observation(upload.offset, upload.status, nowMs)
        }
        entries.values.toList().filter { it.terminalAt == null && it.id !in currentIds }.forEach { entry ->
            val file = files[entry.id]
            entries[entry.id] = if (file != null) entry.copy(
                name = file.name, received = file.size.coerceAtLeast(0), total = file.size.coerceAtLeast(0),
                status = "complete", speed = 0, terminalAt = nowMs
            ) else entry.copy(status = "cancelled", speed = 0, terminalAt = nowMs)
        }
        // A very fast file may already be shared after its bounded completion record was pruned.
        files.values.filter { it.id !in knownFiles && it.id !in currentIds && it.id !in entries }.forEach { file ->
            putEntry(Entry(file.id, file.name, file.size.coerceAtLeast(0), file.size.coerceAtLeast(0), "complete", nowMs, 0, nowMs))
        }
        files.keys.forEach { knownFiles.add(it) }
        trimHistory(currentIds + files.keys)
        return aggregate(nowMs)
    }

    @Synchronized fun tick(generation: Long, nowMs: Long): HostReceiveFeedback? {
        if (generation != this.generation) return null
        if (!started) return null
        expire(nowMs)
        return aggregate(nowMs)
    }

    @Synchronized fun stop(generation: Long, @Suppress("UNUSED_PARAMETER") nowMs: Long): HostReceiveFeedback? {
        this.generation = generation
        started = false
        observed.clear(); knownFiles.clear(); entries.clear()
        return null
    }

    private fun expire(nowMs: Long) {
        entries.entries.removeAll { (_, entry) -> entry.terminalAt?.let { elapsed(nowMs, it) >= terminalForMs } == true }
    }

    private fun putEntry(entry: Entry) {
        entries.remove(entry.id)
        entries[entry.id] = entry
        while (entries.size > MAX_TRACKED) entries.remove(entries.keys.first())
    }

    private fun trimHistory(currentIds: Set<String>) {
        while (observed.size > MAX_TRACKED) observed.remove(observed.keys.firstOrNull { it !in currentIds && it !in entries } ?: observed.keys.first())
        while (knownFiles.size > MAX_TRACKED) knownFiles.remove(knownFiles.firstOrNull { it !in currentIds && it !in entries } ?: knownFiles.first())
    }

    private fun aggregate(nowMs: Long): HostReceiveFeedback? {
        if (entries.isEmpty()) return null
        val all = entries.values.toList()
        val active = all.filter { it.terminalAt == null }
        fun phase(entry: Entry): String = when {
            entry.status in COMPLETE -> "已接收"
            entry.status == "cancelled" -> "接收中断"
            entry.status == "failed" -> "失败"
            entry.status in setOf("verifying", "promoting") || (entry.total != null && entry.received >= entry.total) -> "处理中"
            elapsed(nowMs, entry.lastActivity) >= waitingAfterMs -> "等待数据"
            else -> "接收中"
        }
        val phases = active.map(::phase)
        val currentPhase = if (active.isNotEmpty()) when {
            "接收中" in phases -> "接收中"
            "处理中" in phases -> "处理中"
            else -> "等待数据"
        } else when {
            all.any { it.status == "failed" } -> "失败"
            all.any { it.status == "cancelled" } -> "接收中断"
            else -> "已接收"
        }
        return HostReceiveFeedback(
            phase = currentPhase,
            name = (active.lastOrNull() ?: all.last()).name,
            fileCount = all.size,
            receivedBytes = all.fold(0L) { sum, entry -> add(sum, entry.received) },
            totalBytes = if (all.any { it.total == null }) null else all.fold(0L) { sum, entry -> add(sum, entry.total!!) },
            speedBytesPerSecond = active.fold(0L) { sum, entry -> add(sum, if (phase(entry) == "接收中") entry.speed else 0) },
            active = active.isNotEmpty()
        )
    }

    private fun rate(bytes: Long, millis: Long): Long = if (bytes <= 0 || millis <= 0) 0 else
        (bytes.toDouble() * 1000.0 / millis.toDouble()).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()

    private fun elapsed(now: Long, then: Long): Long = (now - then).coerceAtLeast(0)
    private fun add(left: Long, right: Long): Long = if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private companion object {
        const val MAX_TRACKED = 4096
        val COMPLETE = setOf("complete", "released")
        val TERMINAL = setOf("failed", "cancelled")
    }
}

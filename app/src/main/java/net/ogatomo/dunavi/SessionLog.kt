package net.ogatomo.dunavi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class LogCategory { BT, AT, PPP, LCP, AUTH, IPCP, IP, DNS, TCP, UDP, ERROR, SESSION }

typealias CatLog = (category: LogCategory, direction: String?, message: String) -> Unit

data class LogEntry(
    val sessionId: Long,
    val timestampMs: Long,
    val category: LogCategory,
    val direction: String?,
    val message: String,
) {
    fun format(withSession: Boolean = true, withDate: Boolean = true): String {
        val pattern = if (withDate) "MM-dd HH:mm:ss.SSS" else "HH:mm:ss.SSS"
        val stamp = SimpleDateFormat(pattern, Locale.US).format(Date(timestampMs))
        return buildString {
            append(stamp).append(' ')
            if (withSession) append('#').append(sessionId).append(' ')
            append(category.name)
            direction?.let { append('/').append(it) }
            append(' ').append(message)
        }
    }
}

data class StoredSession(val id: Long, val startedAtMs: Long, val debug: Boolean)
data class ExportedLog(val file: File, val containsSensitiveDetails: Boolean)

/** Async file logging with bounded UI history and a writer owned by each RFCOMM session. */
object SessionLog {
    private const val MAX_FILE_BYTES_DEFAULT = 5L * 1024 * 1024
    private const val MAX_FILES_DEFAULT = 5
    private const val UI_LIMIT = 750
    private const val CHANNEL_CAPACITY = 8_192
    private val FILE_NAME = Regex("DUNavi_(\\d{4}-\\d{2}-\\d{2}_\\d{6})_session-(\\d+)(?:_part-(\\d+))?\\.log")
    private val LEGACY_FILE_NAME = Regex("dunavi-(\\d+)-(\\d{8}-\\d{6})(?:-part(\\d+))?\\.log")
    private val FILE_TIME = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
    private val LEGACY_FILE_TIME = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private data class QueuedEntry(val entry: LogEntry, val visible: Boolean, val closeSession: Boolean = false)
    private data class SessionWriter(
        val id: Long,
        val startedAtMs: Long,
        val debug: Boolean,
        var part: Int = 0,
        var file: File? = null,
        var writer: BufferedWriter? = null,
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<QueuedEntry>(CHANNEL_CAPACITY)
    private val pending = AtomicLong()
    private val dropped = AtomicLong()
    private val sessionCounter = AtomicLong()
    private val started = AtomicBoolean(false)
    private val writers = HashMap<Long, SessionWriter>()
    private val recent = ArrayDeque<LogEntry>(UI_LIMIT)

    @Volatile var detailed: Boolean = false
    @Volatile private var dir: File? = null
    @Volatile private var currentId: Long = 0
    @Volatile internal var maxFileBytes = MAX_FILE_BYTES_DEFAULT
    @Volatile internal var maxFiles = MAX_FILES_DEFAULT

    private val linesFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val lines: StateFlow<List<LogEntry>> = linesFlow
    private val sessionsFlow = MutableStateFlow<List<StoredSession>>(emptyList())
    val sessions: StateFlow<List<StoredSession>> = sessionsFlow
    val currentSessionId: Long get() = currentId

    fun init(directory: File?) {
        if (directory != null) {
            runCatching { directory.mkdirs() }
            dir = directory
            refreshSessions()
        }
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            for (queued in channel) {
                append(queued)
                pending.decrementAndGet()
                if (queued.visible) publish(queued.entry)
            }
        }
    }

    fun beginSession(): Long {
        val id = sessionCounter.incrementAndGet()
        val now = System.currentTimeMillis()
        currentId = id
        synchronized(lock) {
            closeWriter(writers.remove(id))
            val state = SessionWriter(id, now, detailed)
            writers[id] = state
            openPart(state)
        }
        synchronized(recent) { recent.clear() }
        linesFlow.value = emptyList()
        refreshSessions()
        diagnostic(LogCategory.SESSION, message = "SESSION $id START${if (detailed) " (debug; sensitive details possible)" else ""}")
        return id
    }

    fun endSession() {
        val id = currentId
        if (id > 0) enqueue(LogCategory.SESSION, null, "SESSION $id END", force = true, closeSession = true)
    }

    /** Protocol detail. Normal mode retains only errors; debug mode retains everything. */
    fun log(category: LogCategory, direction: String?, message: String) {
        if (!detailed && category != LogCategory.ERROR) return
        enqueue(category, direction, message, force = category == LogCategory.ERROR)
    }

    /** Short event shown and saved in both normal and debug modes. */
    fun diagnostic(category: LogCategory, direction: String? = null, message: String) {
        enqueue(category, direction, message, force = true)
    }

    private fun enqueue(
        category: LogCategory,
        direction: String?,
        message: String,
        force: Boolean,
        closeSession: Boolean = false,
    ) {
        val debug = detailed
        if (!force && !debug) return
        val safeMessage = if (debug) message.take(16_384) else sanitize(message)
        val queued = QueuedEntry(
            LogEntry(currentId, System.currentTimeMillis(), category, direction, safeMessage),
            visible = force || debug,
            closeSession = closeSession,
        )
        pending.incrementAndGet()
        if (!channel.trySend(queued).isSuccess) {
            pending.decrementAndGet()
            dropped.incrementAndGet()
        }
    }

    fun sanitize(message: String): String = message
        .replace(Regex("(?i)(password|passwd|user(name)?|apn|pin|secret)\\s*[=:]\\s*[^ ,;]+"), "$1=***")
        .replace(Regex("(?i)(AT\\+CGDCONT=).*"), "$1<redacted>")
        .replace(Regex("(?i)(AT\\+CGE?Q(?:MIN|REQ)=).*"), "$1<redacted>")
        .replace(Regex("(?i)ATD(?!\\*?(?:98|99))([TP]?)[0-9][0-9*#,;!WABCD+() -]*")) { match ->
            "ATD${match.groupValues[1]}" + match.value.substring(3 + match.groupValues[1].length)
                .map { if (it.isDigit()) 'd' else it }.joinToString("")
        }
        .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}:\\d+\\b"), "<destination>")

    internal fun awaitIdle(timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (pending.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    fun export(outDir: File, sessionId: Long? = null): ExportedLog? {
        awaitIdle()
        val directory = dir ?: return null
        val files = logFiles(directory)
        if (files.isEmpty()) return null
        val selected = if (sessionId == null) files else files.filter { fileSession(it)?.id == sessionId }
        if (selected.isEmpty()) return null
        val containsDetails = selected.any(::isDebugFile)
        return runCatching {
            outDir.mkdirs()
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val suffix = sessionId?.let { "session-${it.toString().padStart(3, '0')}" } ?: "all"
            val out = File(outDir, "DUNavi_${stamp}_$suffix.log")
            out.bufferedWriter().use { writer ->
                writer.appendLine("DUNavi session log")
                writer.appendLine("exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
                if (containsDetails) writer.appendLine("WARNING: debug log may contain APN, credentials, phone numbers, addresses, ports, and destinations.")
                writer.appendLine()
                selected.forEach { file -> file.forEachLine { writer.appendLine(it) } }
            }
            ExportedLog(out, containsDetails)
        }.getOrNull()
    }

    private fun append(queued: QueuedEntry) {
        val id = queued.entry.sessionId
        if (id <= 0) return
        synchronized(lock) {
            val state = writers[id] ?: return
            val lost = dropped.getAndSet(0)
            runCatching {
                state.writer?.apply {
                    if (lost > 0) appendLine(queued.entry.copy(message = "LOGGER DROPPED $lost ENTRIES").format())
                    appendLine(queued.entry.format())
                    flush()
                }
            }.onFailure { closeWriter(state) }
            if ((state.file?.length() ?: 0) >= maxFileBytes) {
                closeWriter(state)
                state.part++
                openPart(state)
            }
            if (queued.closeSession) {
                closeWriter(state)
                writers.remove(id)
                refreshSessions()
            }
        }
    }

    private fun publish(entry: LogEntry) {
        synchronized(recent) {
            if (recent.size == UI_LIMIT) recent.removeFirst()
            recent.addLast(entry)
            linesFlow.value = recent.toList()
        }
    }

    private fun openPart(state: SessionWriter) {
        val directory = dir ?: return
        runCatching {
            val timestamp = FILE_TIME.format(Date(state.startedAtMs))
            val part = if (state.part == 0) "" else "_part-${state.part}"
            state.file = File(directory, "DUNavi_${timestamp}_session-${state.id.toString().padStart(3, '0')}$part.log")
            state.writer = FileWriter(state.file!!, true).buffered()
            state.writer?.apply {
                appendLine("# DUNavi session=${state.id} mode=${if (state.debug) "debug" else "sanitized"} part=${state.part}")
                flush()
            }
            prune(directory)
        }.onFailure {
            closeWriter(state)
            state.file = null
        }
    }

    private fun closeWriter(state: SessionWriter?) {
        runCatching { state?.writer?.flush() }
        runCatching { state?.writer?.close() }
        state?.writer = null
    }

    private fun prune(directory: File) {
        val open = writers.values.mapNotNullTo(HashSet()) { it.file?.absolutePath }
        logFiles(directory)
            .filterNot { it.absolutePath in open }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .drop((maxFiles - open.size).coerceAtLeast(0))
            .forEach { it.delete() }
    }

    private fun refreshSessions() {
        val directory = dir ?: return
        val found = logFiles(directory).mapNotNull(::fileSession)
            .groupBy { it.id }
            .map { (_, parts) -> parts.maxBy { it.startedAtMs }.copy(debug = parts.any { it.debug }) }
            .sortedByDescending { it.startedAtMs }
        sessionsFlow.value = found
        found.maxOfOrNull { it.id }?.let { maximum -> sessionCounter.updateAndGet { maxOf(it, maximum) } }
    }

    private fun fileSession(file: File): StoredSession? {
        FILE_NAME.matchEntire(file.name)?.let { match ->
            val time = runCatching { FILE_TIME.parse(match.groupValues[1])?.time }.getOrNull() ?: file.lastModified()
            return StoredSession(match.groupValues[2].toLong(), time, isDebugFile(file))
        }
        LEGACY_FILE_NAME.matchEntire(file.name)?.let { match ->
            val time = runCatching { LEGACY_FILE_TIME.parse(match.groupValues[2])?.time }.getOrNull() ?: file.lastModified()
            return StoredSession(match.groupValues[1].toLong(), time, isDebugFile(file))
        }
        return null
    }

    private fun isDebugFile(file: File): Boolean = runCatching {
        file.useLines { lines -> lines.take(8).any { "mode=debug" in it || "(debug" in it || "(detailed" in it } }
    }.getOrDefault(false)

    private fun logFiles(directory: File): List<File> = directory
        .listFiles { file -> file.isFile && file.extension.equals("log", ignoreCase = true) }
        .orEmpty()
        .sortedBy { it.name }

    internal fun resetForTest(directory: File? = null) {
        awaitIdle()
        synchronized(lock) {
            writers.values.forEach(::closeWriter)
            writers.clear()
        }
        while (channel.tryReceive().isSuccess) { }
        dir = directory
        directory?.mkdirs()
        currentId = 0
        maxFileBytes = MAX_FILE_BYTES_DEFAULT
        maxFiles = MAX_FILES_DEFAULT
        detailed = false
        sessionCounter.set(0)
        pending.set(0)
        dropped.set(0)
        synchronized(recent) { recent.clear() }
        linesFlow.value = emptyList()
        sessionsFlow.value = emptyList()
        init(directory)
    }
}

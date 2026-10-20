package app.fileflash

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import app.fileflash.core.TransferServer
import app.fileflash.core.ShareFile
import app.fileflash.core.Source
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest

data class AppState(
    val hostRunning: Boolean = false,
    val hostStarting: Boolean = false,
    val hostStopping: Boolean = false,
    val addresses: List<String> = emptyList(),
    val hostState: ServerState = ServerState(),
    val remoteConnected: Boolean = false,
    val remoteConnecting: Boolean = false,
    val remoteAddress: String = "",
    val remoteState: ServerState = ServerState(),
    val remoteError: String? = null,
    val tasks: List<NativeTask> = emptyList(),
    val saved: List<SavedFile> = emptyList(),
    val hostTemporary: List<RemoteFile> = emptyList(),
    val temporaryBytes: Long = 0,
    val reclaimableBytes: Long = 0,
    val cacheCleaning: Boolean = false,
    val notice: String? = null
)

class TransferRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("fileflash", Context.MODE_PRIVATE)
    private val lock = Any()
    private val persistenceLock = Any()
    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()
    @Volatile private var server: TransferServer? = null
    @Volatile private var remote: NativeClient? = null
    private val clients = ConcurrentHashMap<String, NativeClient>()
    private val queue = Mutex()
    private val shareQueue = Mutex()
    private val hostGeneration = AtomicLong()
    private val remoteGeneration = AtomicLong()
    private val hashing = ConcurrentHashMap<String, AtomicBoolean>()
    private val shareSources = ConcurrentHashMap<String, LocalFile>()
    private val hostSamples = mutableMapOf<String, Pair<Long, Long>>()
    private val activeExports = ConcurrentHashMap<String, AtomicBoolean>()
    private var shared = mutableListOf<LocalFile>()
    private val maintenanceLock = Any()
    private var lastMaintenance = 0L
    private val idleTtl = 24 * 60 * 60_000L
    private val idleBudget = 256 * 1024 * 1024L
    private val idleGrace = 30 * 60_000L
    private val hostWake = Channel<Unit>(Channel.CONFLATED)
    private val remoteWake = Channel<Unit>(Channel.CONFLATED)
    private val reboundShares = java.util.WeakHashMap<TransferServer, MutableSet<String>>()
    @Volatile var notificationChanged: (() -> Unit)? = null
    @Volatile var serviceStopped: (() -> Unit)? = null

    init {
        val savedType = object : TypeToken<List<SavedFile>>() {}.type
        val sharedType = object : TypeToken<List<LocalFile>>() {}.type
        val taskType = object : TypeToken<List<NativeTask>>() {}.type
        val saved: List<SavedFile> = runCatching { gson.fromJson<List<SavedFile>>(prefs.getString("saved", "[]"), savedType) }.getOrDefault(emptyList())
        shared = runCatching { gson.fromJson<List<LocalFile>>(prefs.getString("shared", "[]"), sharedType).toMutableList() }.getOrDefault(mutableListOf())
        val tasks: List<NativeTask> = runCatching { gson.fromJson<List<NativeTask>>(prefs.getString("tasks", "[]"), taskType) }.getOrDefault(emptyList())
        _state.update { it.copy(saved = saved, tasks = boundedTasks(tasks.map { t -> if (t.status !in listOf("完成", "失败", "已暂停")) t.copy(status = "已暂停", error = "应用已重启；重新连接后可重试") else t })) }
        Storage.recoverPublications(context).forEach { recovered -> runCatching { addSaved(recovered) } }
        scope.launch { maintainStorage(force = true) }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { updateAddresses() }
            override fun onLost(network: Network) { updateAddresses() }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { updateAddresses() }
        }) }
        scope.launch {
            while (true) {
                val s = server
                if (s != null) {
                    runCatching { gson.fromJson(s.state(), ServerState::class.java) }.onSuccess { value ->
                        _state.update { it.copy(hostState = withHostSpeeds(value)) }
                        value.files.filter { it.received }.forEach { item ->
                            _state.value.saved.firstOrNull { it.id == item.id }?.let { releaseSavedCache(s, it, item) }
                        }
                        refreshHostTemporary(s, value)
                    }.onFailure { notice("读取传输状态失败：${it.message}") }
                    updateAddresses()
                }
                notificationChanged?.invoke()
                if (System.currentTimeMillis() - lastMaintenance > 60_000) maintainStorage()
                if (s != null || remote != null || clients.isNotEmpty() || activeExports.isNotEmpty()) withTimeoutOrNull(900L) { hostWake.receive() }
                else if (_state.value.temporaryBytes > 0) withTimeoutOrNull(60_000L) { hostWake.receive() }
                else hostWake.receive()
            }
        }
        scope.launch {
            while (true) {
                val r = remote
                if (r != null) runCatching { r.state() }.onSuccess { value ->
                    if (remote === r) _state.update { it.copy(remoteState = value, remoteError = null, remoteConnected = true) }
                }.onFailure { e -> if (remote === r) {
                    if (e is HttpFailure && e.statusCode in listOf(401, 403)) {
                        remote = null
                        _state.update { it.copy(remoteConnected = false, remoteState = ServerState(), remoteError = "连接授权已失效，请重新扫码") }
                    } else _state.update { it.copy(remoteError = networkError(e)) }
                } }
                if (r != null) withTimeoutOrNull(1000L) { remoteWake.receive() } else remoteWake.receive()
            }
        }
    }

    fun notice(message: String?) { _state.update { it.copy(notice = message) } }

    private fun withHostSpeeds(value: ServerState): ServerState {
        val now = System.nanoTime()
        val visible = value.uploads.map { "u-${it.id}" }.toSet() + value.downloads.map { "d-${it.id}" }
        hostSamples.keys.retainAll(visible)
        fun speed(id: String, done: Long, active: Boolean): Long {
            val previous = hostSamples.put(id, done to now) ?: return 0
            val elapsed = now - previous.second
            return if (active && elapsed > 0) ((done - previous.first).coerceAtLeast(0) * 1_000_000_000 / elapsed) else 0
        }
        return value.copy(uploads = value.uploads.map { it.copy(speed = speed("u-${it.id}", it.offset, it.status == "uploading")) }, downloads = value.downloads.map { it.copy(speed = speed("d-${it.id}", it.transferred, it.status == "downloading")) })
    }

    fun startHost() {
        if (server != null || _state.value.hostStarting) return
        if (_state.value.hostStopping) return notice("正在停止，请稍后重试")
        val generation = hostGeneration.incrementAndGet()
        _state.update { it.copy(hostStarting = true) }
        scope.launch {
            var fresh: TransferServer? = null
            try {
                // Offline save retries own the same private files. Await them without holding lock.
                while (activeExports.isNotEmpty()) {
                    if (generation != hostGeneration.get()) return@launch
                    delay(50)
                }
                if (generation != hostGeneration.get()) return@launch
                val created = synchronized(lock) { TransferServer(File(context.filesDir, "server")) { path -> runCatching { context.assets.open("web/$path") }.getOrNull() } }
                fresh = created
                restoreSavedShares(created)
                val previousPort = prefs.getInt("hostPort", 8787)
                try { created.start(previousPort) } catch (_: java.net.BindException) { created.stop(); created.start(0) }
                prefs.edit().putInt("hostPort", created.port).apply()
                if (generation != hostGeneration.get()) { created.stop(); return@launch }
                synchronized(lock) {
                    if (generation != hostGeneration.get()) { created.stop(); return@launch }
                    server = created
                    hostWake.trySend(Unit)
                    _state.update { it.copy(hostRunning = true, hostStarting = false) }
                    shared.toList().forEach { prepareShare(created, it, generation) }
                }
                updateAddresses()
            } catch (e: Exception) {
                runCatching { fresh?.stop() }
                if (generation == hostGeneration.get()) _state.update { it.copy(hostStarting = false, hostRunning = false, notice = "启动失败：${e.message}") }
                stopServiceIfIdle()
            }
        }
    }

    fun stopHost() {
        hostGeneration.incrementAndGet()
        hashing.values.forEach { it.set(true) }
        val old = synchronized(lock) { val existing = server; server = null; existing }
        shareSources.clear()
        _state.update { it.copy(hostRunning = false, hostStarting = false, hostStopping = old != null || it.hostStopping, addresses = emptyList(), hostState = ServerState()) }
        if (old != null) scope.launch {
            runCatching { old.stop() }
            _state.update { it.copy(hostStopping = false) }
            stopServiceIfIdle()
        } else stopServiceIfIdle()
    }

    private fun updateAddresses() {
        val s = server ?: return
        val addresses = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val wifiAddresses = cm.allNetworks.flatMap { network ->
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
                    cm.getLinkProperties(network)?.linkAddresses?.mapNotNull { (it.address as? Inet4Address)?.hostAddress } ?: emptyList()
                } else emptyList()
            }.filter { it != "127.0.0.1" }
            val interfaceAddresses = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni") && !it.name.startsWith("pdp") && !it.name.startsWith("tun") }.flatMap { n ->
                n.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }.map { it.hostAddress ?: "" }
            }
            (wifiAddresses + interfaceAddresses).distinct()
        }.getOrDefault(emptyList())
        _state.update { it.copy(addresses = addresses.map { ip -> "http://$ip:${s.port}/#pair=${s.pairToken}" }) }
    }

    fun addShared(uris: List<android.net.Uri>) {
        scope.launch {
            var count = 0
            uris.forEach { uri ->
                runCatching {
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    val item = Storage.describe(context, uri)
                    synchronized(lock) {
                        if (shared.none { it.uri == item.uri }) {
                            shared.add(item)
                            prefs.edit().putString("shared", gson.toJson(shared)).apply()
                            server?.let { prepareShare(it, item, hostGeneration.get()) }
                            count++
                        }
                    }
                }.onFailure { notice("文件选择失败：${it.message}") }
            }
            if (count > 0) notice("已添加 $count 个文件，校验后可接收")
        }
    }

    private fun prepareShare(s: TransferServer, file: LocalFile, generation: Long) {
        val task = NativeTask(name = file.name, size = file.size, direction = "共享", source = file)
        val cancelled = AtomicBoolean(false)
        hashing[task.id] = cancelled
        addTask(task); persistTasks()
        scope.launch { shareQueue.withLock {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                Storage.stream(context, file).use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var lastProgress = 0L
                    while (true) {
                        if (cancelled.get() || generation != hostGeneration.get()) throw TransferCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n); done += n
                        if (done > file.size) throw IOException("源文件大小已变更")
                        val now = System.nanoTime()
                        if (now - lastProgress > 250_000_000) { updateTask(task.id) { it.copy(transferred = done, status = "校验中") }; lastProgress = now }
                    }
                }
                if (done != file.size) throw IOException("源文件大小已变更")
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                synchronized(lock) {
                    if (cancelled.get() || generation != hostGeneration.get() || server !== s) throw TransferCancelled()
                    val published = shareSource(s, file, hash)
                    shareSources[published.id] = file
                }
                updateTask(task.id) { it.copy(transferred = file.size, status = "完成") }
            } catch (e: Exception) {
                updateTask(task.id) { it.copy(status = if (e is TransferCancelled) "已暂停" else "失败", error = networkError(e)) }
            } finally { hashing.remove(task.id); persistTasks() }
        } }
    }

    private fun shareSource(s: TransferServer, file: LocalFile, sha256: String): ShareFile {
        return s.share(file.name, file.size, file.mime, sha256, sourceFor(file))
    }

    private fun sourceFor(file: LocalFile) = Source { offset ->
            val stream = Storage.stream(context, file)
            try {
                var remaining = offset
                while (remaining > 0) {
                    val skipped = stream.skip(remaining)
                    if (skipped > 0) remaining -= skipped else {
                        if (stream.read() == -1) throw IOException("文件大小已变更")
                        remaining--
                    }
                }
                stream
            } catch (e: Exception) { stream.close(); throw e }
        }

    private fun savedSource(file: SavedFile) = sourceFor(LocalFile(file.uri, file.name, file.size, file.mime))

    private fun restoreSavedShares(engine: TransferServer) {
        val received = gson.fromJson(engine.state(), ServerState::class.java).files.associateBy { it.id }
        _state.value.saved.forEach { saved ->
            val original = received[saved.id]
            if (engine.receivedFile(saved.id) != null) releaseSavedCache(engine, saved, original)
            else if (saved.hostReceived && !saved.hiddenFromHost && Storage.isReadable(context, saved)) {
                runCatching { engine.restoreReceivedShare(saved.id, saved.name, saved.size, saved.mime, saved.sha256, saved.createdAt, savedSource(saved)) }
            }
        }
    }

    private fun releaseSavedCache(engine: TransferServer, snapshot: SavedFile, original: RemoteFile? = null): Long = synchronized(lock) {
        val saved = _state.value.saved.firstOrNull { it.id == snapshot.id } ?: snapshot
        val privateFile = engine.receivedFile(saved.id)
        if (privateFile == null) {
            // An offline save-retry engine may have released bytes while this host was starting.
            // Replace its stale private opener once, while keeping deliberately hidden shares hidden.
            if (saved.hostReceived && !saved.hiddenFromHost && original != null && Storage.isReadable(context, saved)) {
                val needed = synchronized(reboundShares) { reboundShares.getOrPut(engine) { mutableSetOf() }.add(saved.id) }
                if (needed) runCatching { engine.restoreReceivedShare(saved.id, saved.name, saved.size, saved.mime, saved.sha256, saved.createdAt, savedSource(saved)) }
                    .onFailure { synchronized(reboundShares) { reboundShares[engine]?.remove(saved.id) } }
            }
            return@synchronized 0L
        }
        if (!Storage.isReadable(context, saved)) return@synchronized 0L
        val durable = if ((!saved.hostReceived && !saved.hiddenFromHost) || (saved.sha256 == null && original?.sha256 != null) || (saved.createdAt == 0L && original != null)) saved.copy(hostReceived = !saved.hiddenFromHost, sha256 = original?.sha256 ?: saved.sha256, createdAt = saved.createdAt.takeIf { it > 0 } ?: original?.createdAt ?: 0) else saved
        if (durable != saved) addSaved(durable)
        val size = privateFile.length()
        runCatching { engine.releaseReceivedCache(saved.id, savedSource(durable)); size }.getOrDefault(0L)
    }

    fun removeShared(id: String, name: String) {
        scope.launch {
            runCatching {
                _state.value.saved.firstOrNull { it.id == id && it.hostReceived }?.let { saved -> addSaved(saved.copy(hostReceived = false, hiddenFromHost = true)) }
                synchronized(lock) {
                    server?.removeFile(id)
                    val selected = shareSources.remove(id)
                    if (selected != null) shared.removeAll { it.uri == selected.uri }
                    prefs.edit().putString("shared", gson.toJson(shared)).apply()
                }
                maintainStorage(force = true)
            }.onFailure { notice("移除失败：${it.message}") }
        }
    }

    fun revoke(id: String) { server?.revokeClient(id) }
    fun cancelHostUpload(id: String) { scope.launch { runCatching { server?.cancelUpload(id); maintainStorage(force = true) }.onFailure { notice("取消失败：${it.message}") } } }
    fun cancelHostDownload(id: String) { server?.cancelDownload(id) }
    fun sendText(text: String, toRemote: Boolean, completed: (Boolean) -> Unit = {}) {
        if (text.isBlank()) return
        scope.launch {
            runCatching {
                if (toRemote) (remote ?: throw IOException("请先连接设备")).sendText(text) else (server ?: throw IOException("请先启动传输")).sendText(text)
            }.onSuccess { notice("已发送"); scope.launch(Dispatchers.Main) { completed(true) } }.onFailure { notice("文字发送失败：${networkError(it)}"); scope.launch(Dispatchers.Main) { completed(false) } }
        }
    }

    fun connect(address: String, token: String) {
        if (_state.value.remoteConnecting) return
        val generation = remoteGeneration.incrementAndGet()
        _state.update { it.copy(remoteConnecting = true, remoteError = null) }
        scope.launch {
            runCatching { NativeClient.pair(context, address, token) }.onSuccess { (client, endpoint) ->
                if (generation != remoteGeneration.get()) { client.cancel(); return@onSuccess }
                remote?.let { previous -> val revoke = previous.taskClient(); previous.cancel(); scope.launch { runCatching { revoke.request("DELETE", "/api/session") } } }; remote = client
                remoteWake.trySend(Unit); hostWake.trySend(Unit)
                _state.update { it.copy(remoteConnecting = false, remoteConnected = true, remoteAddress = endpoint, remoteError = null, remoteState = ServerState()) }
            }.onFailure { if (generation == remoteGeneration.get()) _state.update { s -> s.copy(remoteConnecting = false, remoteError = networkError(it)) } }
        }
    }

    fun disconnect() {
        remoteGeneration.incrementAndGet()
        remote?.let { previous -> val revoke = previous.taskClient(); previous.cancel(); scope.launch { runCatching { revoke.request("DELETE", "/api/session") } } }; remote = null
        clients.values.forEach { it.cancel() }
        _state.update { it.copy(remoteConnected = false, remoteConnecting = false, remoteState = ServerState(), remoteError = null, remoteAddress = "") }
        stopServiceIfIdle()
    }

    fun upload(uris: List<android.net.Uri>) {
        val client = remote ?: return notice("请先连接设备")
        scope.launch {
            uris.forEach { uri ->
                runCatching {
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    val file = Storage.describe(context, uri)
                    val task = NativeTask(name = file.name, size = file.size, direction = "发送", source = file, endpoint = client.endpoint)
                    addTask(task)
                    persistTasks()
                    runTask(task, client.taskClient())
                }.onFailure { notice("发送失败：${it.message}") }
            }
        }
    }

    fun download(file: RemoteFile) {
        val client = remote ?: return notice("请先连接设备")
        val task = NativeTask(name = file.name, size = file.size, direction = "接收", remote = file, endpoint = client.endpoint)
        addTask(task)
        persistTasks()
        runTask(task, client.taskClient())
    }

    fun retry(task: NativeTask) {
        if (task.direction == "保存") { saveHostFile(task.id.removePrefix("save-")); return }
        if (task.direction == "共享") {
            val s = server ?: return notice("请先开启共享")
            task.source?.let { prepareShare(s, it, hostGeneration.get()) }
            return
        }
        val client = remote ?: return notice("请先重新连接原设备")
        if (task.endpoint != null && task.endpoint != client.endpoint) return notice("此任务属于 ${task.endpoint}，请重新连接原设备")
        if (clients.containsKey(task.id)) return notice("正在暂停，请稍后重试")
        updateTask(task.id) { it.copy(status = "等待", error = null, speed = 0) }
        persistTasks()
        runTask(task, client.taskClient())
    }

    private fun runTask(task: NativeTask, client: NativeClient) {
        synchronized(maintenanceLock) { clients[task.id] = client; hostWake.trySend(Unit) }
        try { TransferService.ensure(context) } catch (e: Exception) {
            clients.remove(task.id)
            updateTask(task.id) { it.copy(status = "失败", error = "无法在后台启动传输，请回到应用重试：${e.message}") }
            persistTasks(); return
        }
        scope.launch {
            queue.withLock {
                var lastTime = System.nanoTime()
                var lastBytes = task.transferred
                var speed = 0L
                fun progress(done: Long, status: String) {
                    val now = System.nanoTime(); val elapsed = now - lastTime
                    if (elapsed > 300_000_000) { speed = ((done - lastBytes).coerceAtLeast(0) * 1_000_000_000 / elapsed); lastTime = now; lastBytes = done }
                    updateTask(task.id) { it.copy(transferred = done, status = status, speed = if (status == "传输中") speed else 0, error = null) }
                }
                try {
                    if (client.cancelled.get()) throw TransferCancelled()
                    updateTask(task.id) { it.copy(status = "连接中") }; persistTasks()
                    if (task.source != null) client.upload(task.source, ::progress)
                    else if (task.remote != null) {
                        Storage.recoverPublications(context, cleanupPending = false).forEach { addSaved(it) }
                        val saved = _state.value.saved.firstOrNull { it.id == task.id && Storage.isReadable(context, it) }
                            ?: client.download(task.remote, task.id, ::progress)
                        addSaved(saved)
                        updateTask(task.id) { it.copy(savedUri = saved.uri) }
                    } else throw IOException("源文件不可用")
                    updateTask(task.id) { it.copy(transferred = task.size, status = "完成", speed = 0) }
                } catch (e: Exception) {
                    updateTask(task.id) { it.copy(status = if (e is TransferCancelled) "已暂停" else "失败", error = networkError(e), speed = 0) }
                } finally {
                    clients.remove(task.id)
                    persistTasks()
                    runCatching { maintainStorage(force = true) }
                    stopServiceIfIdle()
                }
            }
        }
    }

    fun cancelTask(id: String) { clients[id]?.cancel(); hashing[id]?.set(true); if (id.startsWith("save-")) activeExports[id.removePrefix("save-")]?.set(true); updateTask(id) { it.copy(status = "已暂停", speed = 0) }; persistTasks() }
    private fun boundedTasks(tasks: List<NativeTask>): List<NativeTask> {
        var completed = 0
        return tasks.filter { it.status != "完成" || completed++ < 150 }
    }
    private fun updateTask(id: String, update: (NativeTask) -> NativeTask) { _state.update { state -> state.copy(tasks = boundedTasks(state.tasks.map { if (it.id == id) update(it).copy(updatedAt = System.currentTimeMillis()) else it })) }; notificationChanged?.invoke() }
    private fun persistTasks() { synchronized(persistenceLock) { prefs.edit().putString("tasks", gson.toJson(_state.value.tasks)).apply() } }
    private fun addTask(task: NativeTask) { _state.update { state ->
        state.copy(tasks = boundedTasks(listOf(task) + state.tasks))
    } }
    // Keep publication identity independently of visible recency, so restart never re-exports old files.
    private fun addSaved(file: SavedFile) {
        synchronized(persistenceLock) {
            // A stale crash journal must never undo a later, durably recorded “remove from sharing”.
            val previous = _state.value.saved.firstOrNull { it.id == file.id }
            val durable = if (previous?.hiddenFromHost == true) file.copy(hiddenFromHost = true, hostReceived = false) else file
            val saved = listOf(durable) + _state.value.saved.filter { it.id != file.id }
            if (!prefs.edit().putString("saved", gson.toJson(saved)).commit()) throw IOException("无法记录文件保存位置，请释放空间后重试")
            _state.update { it.copy(saved = saved) }
            Storage.acknowledgePublication(context, file.id)
        }
    }
    private fun refreshHostTemporary(engine: TransferServer, snapshot: ServerState) {
        val savedById = _state.value.saved.associateBy { it.id }
        val pending = snapshot.files.filter { it.received && savedById[it.id]?.let { saved -> Storage.isReadable(context, saved) } != true && engine.receivedFile(it.id) != null }
        _state.update { it.copy(hostTemporary = pending) }
    }

    /** Upload completion only makes a relay file available. This is the explicit save action. */
    fun saveHostFile(id: String) {
        val cancelled = AtomicBoolean(false)
        synchronized(lock) {
            if (_state.value.hostStarting || _state.value.hostStopping) return notice("请等待共享状态更新后重试")
            if (activeExports.isNotEmpty()) return notice("正在保存另一个文件，请稍后重试")
            activeExports[id] = cancelled
        }
        hostWake.trySend(Unit)
        scope.launch {
            var owned: TransferServer? = null
            val taskId = "save-$id"
            try {
                val (engine, item, claim) = synchronized(maintenanceLock) { synchronized(lock) {
                    if (_state.value.hostStarting || _state.value.hostStopping) throw IOException("请等待共享状态更新后重试")
                    if (cancelled.get()) throw TransferCancelled()
                    val engine = server ?: newEngine().also { owned = it }
                    val item = gson.fromJson(engine.state(), ServerState::class.java).files.firstOrNull { it.id == id && it.received }
                        ?: throw IOException("临时文件已清理或不再可用，请重新上传")
                    Triple(engine, item, engine.claimReceivedSave(id))
                } }
                var lastProgress = 0L
                val saved = claim.use { pinned ->
                    val task = NativeTask(id = taskId, name = item.name, size = item.size, direction = "保存", status = "保存中", remote = item)
                    if (_state.value.tasks.none { it.id == taskId }) addTask(task) else updateTask(taskId) { task }
                    persistTasks()
                    TransferService.ensure(context)
                    Storage.publish(context, id, pinned.file(), item.name, item.mime, sha256 = item.sha256, hostReceived = true, progress = { done ->
                        val now = System.nanoTime()
                        if (now - lastProgress > 250_000_000) { updateTask(taskId) { it.copy(transferred = done) }; lastProgress = now }
                    }, cancelled = { cancelled.get() }).also { addSaved(it) }
                }
                releaseSavedCache(engine, saved, item)
                updateTask(taskId) { it.copy(status = "完成", transferred = item.size, savedUri = saved.uri, error = null) }
                notice("已保存到下载 / FileFlash")
            } catch (e: Exception) {
                updateTask(taskId) { it.copy(status = if (e is TransferCancelled) "已暂停" else "失败", error = e.message) }
                notice("保存失败：${e.message}")
            } finally {
                // Offline engines must close before maintenance or host startup opens the same metadata.
                runCatching { owned?.close() }
                activeExports.remove(id)
                persistTasks()
                runCatching { maintainStorage(force = true) }
                hostWake.trySend(Unit); stopServiceIfIdle()
            }
        }
    }

    fun discardHostFile(id: String) {
        scope.launch {
            runCatching {
                synchronized(maintenanceLock) { synchronized(lock) {
                    if (_state.value.hostStarting || _state.value.hostStopping || activeExports.isNotEmpty()) throw IOException("任务正在处理中，请稍后重试")
                    if (_state.value.saved.any { it.id == id && Storage.isReadable(context, it) }) throw IOException("文件已保存，请从系统文件管理器管理")
                    val current = server
                    val engine = current ?: newEngine()
                    try { engine.removeReceivedFile(id); refreshHostTemporary(engine, gson.fromJson(engine.state(), ServerState::class.java)) }
                    finally { if (current == null) engine.close() }
                    _state.update { it.copy(tasks = it.tasks.filterNot { task -> task.id == "save-$id" }) }
                    persistTasks()
                } }
                maintainStorage(force = true)
            }.onSuccess { notice("已丢弃临时文件") }.onFailure { notice("丢弃失败：${it.message}") }
        }
    }
    fun retrySaving() {
        scope.launch {
            if (_state.value.hostStopping || _state.value.hostStarting) return@launch notice("请等待共享状态更新后重试")
            Storage.recoverPublications(context, cleanupPending = false).forEach { recovered -> runCatching { addSaved(recovered) } }
            // Only retry a save the host previously requested; never save all relay files.
            _state.value.tasks.firstOrNull { it.direction == "保存" && it.status in listOf("失败", "已暂停", "已取消") }
                ?.let { saveHostFile(it.id.removePrefix("save-")) }
        }
    }
    fun clearReceivedCache() {
        scope.launch {
            if (_state.value.hostStopping || _state.value.hostStarting) return@launch notice("请等待共享状态更新后重试")
            if (_state.value.cacheCleaning) return@launch
            _state.update { it.copy(cacheCleaning = true) }
            runCatching {
                maintainStorage(force = true, explicit = true)
            }.onSuccess { notice("已清理 ${bytes(it)}；已保存文件和进行中的传输保留") }.onFailure { notice("清理失败：${it.message}") }
            _state.update { it.copy(cacheCleaning = false) }
        }
    }

    fun discardTask(task: NativeTask) {
        if (task.direction == "保存") { discardHostFile(task.id.removePrefix("save-")); return }
        scope.launch {
            if (_state.value.hostStarting || _state.value.hostStopping) return@launch notice("请等待共享状态更新后重试")
            if (clients.containsKey(task.id) || hashing.containsKey(task.id) || (task.id.startsWith("save-") && activeExports.containsKey(task.id.removePrefix("save-")))) return@launch notice("任务仍在处理中，请暂停后稍候")
            if (task.status !in listOf("失败", "已暂停", "已取消")) return@launch
            runCatching {
                synchronized(maintenanceLock) {
                    if (task.direction == "保存") {
                        synchronized(lock) {
                            val current = server
                            if (current == null && (_state.value.hostStarting || _state.value.hostStopping)) throw IOException("请等待共享状态更新后重试")
                            val engine = current ?: newEngine()
                            try {
                                val id = task.id.removePrefix("save-")
                                if (_state.value.saved.none { it.id == id && Storage.isReadable(context, it) } && engine.receivedFile(id) != null) engine.removeReceivedFile(id)
                            } finally { if (current == null) engine.close() }
                        }
                    } else discardLocalTask(task)
                    _state.update { it.copy(tasks = it.tasks.filterNot { t -> t.id == task.id }) }
                    persistTasks()
                }
                maintainStorage(force = true)
            }.onSuccess { notice("已丢弃未完成任务，原文件不受影响") }.onFailure { notice("丢弃失败：${it.message}") }
        }
    }

    private fun newEngine() = TransferServer(File(context.filesDir, "server")) { path -> runCatching { context.assets.open("web/$path") }.getOrNull() }

    fun refreshStorageInfo() { scope.launch { runCatching { maintainStorage(force = true) } } }

    private fun discardLocalTask(task: NativeTask): Long {
        var removed = 0L
        val dir = File(context.filesDir, "client-parts")
        val part = File(dir, "${task.id}.part")
        if (part.isFile) { val size = part.length(); if (!part.delete()) throw IOException("临时文件仍被占用，请稍后重试"); removed += size }
        File(dir, "${task.id}.verified").delete()
        if (task.source != null && task.endpoint != null && task.direction == "发送") {
            val resume = NativeClient.forgetUploadResume(context, task.source, task.endpoint)
            val client = remote
            if (resume != null && client?.endpoint == task.endpoint) scope.launch {
                runCatching { client.taskClient().request("DELETE", "/api/uploads/${resume.id}", headers = mapOf("X-Upload-Key" to resume.key)) }
            }
        }
        return removed
    }

    /** Runs only while the app process is alive. Recent/active transfers and verified unsaved files are protected. */
    private fun maintainStorage(force: Boolean = false, explicit: Boolean = false): Long = synchronized(maintenanceLock) {
        val now = System.currentTimeMillis()
        if (!force && now - lastMaintenance < 60_000) return@synchronized 0L
        if (_state.value.hostStarting || _state.value.hostStopping) return@synchronized 0L
        lastMaintenance = now
        var removed = 0L
        val dir = File(context.filesDir, "client-parts")
        val tasks = _state.value.tasks.associateBy { it.id }
        val candidates = (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".part") && !clients.containsKey(it.name.removeSuffix(".part")) }.sortedBy { it.lastModified() }
        fun protectedPart(file: File): Boolean {
            val id = file.name.removeSuffix(".part")
            val task = tasks[id]
            return File(dir, "$id.verified").exists() || (task?.remote != null && file.length() == task.size)
        }
        var idleBytes = candidates.filterNot(::protectedPart).sumOf { it.length() }
        val cleanedTasks = mutableSetOf<String>()
        candidates.forEach { part ->
            val id = part.name.removeSuffix(".part")
            val task = tasks[id]
            val verified = protectedPart(part)
            val age = (now - maxOf(part.lastModified(), task?.updatedAt ?: 0)).coerceAtLeast(0)
            val saved = _state.value.saved.any { it.id == id && Storage.isReadable(context, it) }
            val discard = saved || (!verified && ((explicit && task?.status in listOf("已暂停", "失败", "已取消")) || task == null || age >= idleTtl || (idleBytes > idleBudget && age >= idleGrace)))
            if (discard) {
                val size = part.length()
                if (part.delete()) {
                    removed += size
                    if (!verified) idleBytes -= size
                    File(dir, "$id.verified").delete()
                    if (task != null && !saved) cleanedTasks.add(id)
                }
            }
        }
        // Explicit clearing also forgets sender resume credentials, which contain no file bytes.
        if (explicit) _state.value.tasks.filter { it.direction != "保存" && it.status in listOf("已暂停", "失败", "已取消") && !clients.containsKey(it.id) && !hashing.containsKey(it.id) && !protectedPart(File(dir, "${it.id}.part")) }.forEach { task ->
            removed += discardLocalTask(task); cleanedTasks.add(task.id)
        }
        if (cleanedTasks.isNotEmpty()) {
            _state.update { state -> state.copy(tasks = state.tasks.filterNot { it.id in cleanedTasks }) }
            persistTasks()
        }
        (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".verified") && !File(dir, it.name.removeSuffix(".verified") + ".part").exists() }.forEach { it.delete() }
        val resumePrefs = context.getSharedPreferences("client-resume", Context.MODE_PRIVATE)
        resumePrefs.all.forEach { (key, raw) ->
            val resume = runCatching { gson.fromJson(raw as String, UploadResume::class.java) }.getOrNull()
            val active = _state.value.tasks.any { it.source?.uri == resume?.uri && clients.containsKey(it.id) }
            if (!active && (resume == null || now - resume.updatedAt >= idleTtl)) resumePrefs.edit().remove(key).apply()
        }
        synchronized(lock) {
            val current = server
            if (current == null && (_state.value.hostStarting || _state.value.hostStopping || activeExports.isNotEmpty())) return@synchronized
            val engine = current ?: newEngine()
            try {
                val originalFiles = gson.fromJson(engine.state(), ServerState::class.java).files.associateBy { it.id }
                _state.value.saved.forEach { removed += releaseSavedCache(engine, it, originalFiles[it.id]) }
                // One shared idle-data target across native receiving and the browser upload engine.
                val remainingBudget = (idleBudget - idleBytes).coerceAtLeast(0)
                removed += engine.cleanupStaleUploads(if (explicit) 0 else idleTtl, if (explicit) 0 else remainingBudget, now)
                refreshHostTemporary(engine, gson.fromJson(engine.state(), ServerState::class.java))
                val nativeReclaimable = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".part") && !clients.containsKey(it.name.removeSuffix(".part")) && !protectedPart(it) }.sumOf { it.length() }
                val temporary = (dir.listFiles() ?: emptyArray()).filter { it.isFile }.sumOf { it.length() } + File(context.filesDir, "server/uploads").listFiles().orEmpty().filter { it.name.endsWith(".part") }.sumOf { it.length() } + File(context.filesDir, "server/received").listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
                _state.update { it.copy(temporaryBytes = temporary, reclaimableBytes = nativeReclaimable + engine.reclaimableCacheBytes()) }
            } finally { if (current == null) engine.close() }
        }
        removed
    }
    fun stopEverything(reason: String? = null) { clients.values.forEach { it.cancel() }; activeExports.values.forEach { it.set(true) }; disconnect(); stopHost(); if (reason != null) notice(reason) }
    fun hasWork(): Boolean = server != null || _state.value.hostStarting || _state.value.hostStopping || clients.isNotEmpty() || activeExports.isNotEmpty()
    private fun stopServiceIfIdle() { if (!hasWork()) serviceStopped?.invoke() }
    fun openSaved(file: SavedFile) { runCatching { Storage.open(context, file) }.onFailure { notice("无法打开：${it.message}") } }

    private fun networkError(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException -> "连接超时；确认两台设备在同一 Wi-Fi，且对方仍在传输"
        is java.net.ConnectException -> "无法连接；确认地址、同一 Wi-Fi 和对方传输状态"
        is java.net.UnknownHostException -> "无法识别连接地址"
        is SecurityException -> "权限不足；请在系统设置中允许局域网或文件访问"
        else -> e.message ?: "连接已中断，可重试"
    }
}

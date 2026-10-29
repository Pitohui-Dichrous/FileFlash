package app.fileflash

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private val Background: Color @Composable get() = acid.canvas
private val Ink: Color @Composable get() = acid.ink
private val Blue: Color @Composable get() = if (acid.dark) AcidLime else AcidElectric
private val Muted: Color @Composable get() = acid.muted
private val Border: Color @Composable get() = acid.rule
private val Error: Color @Composable get() = acid.error
private val Corner = AcidSquare

class MainActivity : ComponentActivity() {
    private val taskIcon by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_art, BitmapFactory.Options().apply { inSampleSize = 4 })
    }
    private val repository get() = (application as FileFlashApplication).repository
    private var pendingNetwork: (() -> Unit)? = null
    private var pendingNotification: (() -> Unit)? = null
    private var scanned by mutableStateOf<String?>(null)
    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) repository.addShared(it) }
    private val uploads = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) repository.upload(it) }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) openScanner() else repository.notice("相机权限未开启；可粘贴链接连接，或在系统设置中允许相机")
    }
    private val networkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val action = pendingNetwork; pendingNetwork = null
        if (allowed) action?.invoke() else repository.notice("局域网权限未开启；请在系统设置中允许后重试")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed) repository.notice("通知已关闭；可在系统设置中开启传输进度通知")
        val action = pendingNotification; pendingNotification = null
        runCatching { action?.invoke() }.onFailure { repository.notice("无法启动传输：${it.message}") }
    }
    private val scanner = registerForActivityResult(ScanContract()) { result -> if (!result.contents.isNullOrBlank()) scanned = result.contents }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshTaskIdentity()
        enableEdgeToEdge()
        setContent {
            AcidTheme {
                val state by repository.state.collectAsStateWithLifecycle()
                FileFlashScreen(state, repository, scanned, onScanned = { scanned = null },
                    onStart = { withNetwork { withNotification { TransferService.startHost(this) } } },
                    onScan = { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openScanner() else cameraPermission.launch(Manifest.permission.CAMERA) },
                    onPickFiles = { files.launch(arrayOf("*/*")) }, onUpload = { uploads.launch(arrayOf("*/*")) },
                    onConnect = { address, token -> withNetwork { withNotification { repository.connect(address, token) } } },
                    onCopy = { text -> (getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText("FileFlash", text)); repository.notice("已复制") },
                    onSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTaskIdentity()
    }

    @Suppress("DEPRECATION")
    private fun refreshTaskIdentity() {
        setTaskDescription(ActivityManager.TaskDescription("FileFlash", taskIcon, android.graphics.Color.rgb(36, 36, 36)))
    }

    private fun withNetwork(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(this, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            pendingNetwork = action; networkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
        } else action()
    }
    private fun withNotification(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingNotification = action; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
    private fun openScanner() { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描 FileFlash 二维码").setBeepEnabled(false).setOrientationLocked(false).addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileFlashScreen(state: AppState, repository: TransferRepository, scanned: String?, onScanned: () -> Unit, onStart: () -> Unit, onScan: () -> Unit, onPickFiles: () -> Unit, onUpload: () -> Unit, onConnect: (String, String) -> Unit, onCopy: (String) -> Unit, onSettings: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(0) }
    var address by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var qr by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var lastReceiveEndpoint by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); repository.notice(null) } }
    LaunchedEffect(scanned) { if (scanned != null) { address = scanned; page = 1; onConnect(scanned, ""); onScanned() } }
    LaunchedEffect(state.remoteAddress) { if (state.remoteAddress.isNotBlank()) lastReceiveEndpoint = state.remoteAddress }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(modifier = Modifier.imePadding(), containerColor = Background,
        topBar = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AcidMasthead(onInfo = { showInfo = true })
                if (state.hostRunning) state.hostReceiveFeedback?.let { HostReceiveProgress(it) }
            }
        },
        bottomBar = { if (!keyboardVisible) AcidNavigation(page) { page = it } },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when (page) {
                0 -> HostPage(state, repository, onStart, onPickFiles, onQr = { qr = it }, onCopy)
                1 -> RemotePage(state, repository, address, { address = it }, token, { token = it }, onScan, onConnect, onUpload, state.remoteAddress.ifBlank { lastReceiveEndpoint })
                2 -> TextPage(state, repository, onCopy)
                3 -> HistoryPage(state, repository)
            }
        }
    }
    qr?.let { value -> AlertDialog(onDismissRequest = { qr = null }, title = { Text("扫码连接") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { QrImage(value); Spacer(Modifier.height(12.dp)); SelectionContainer { Text(value.substringBefore("/#"), fontSize = 14.sp) } } }, confirmButton = { TextButton(onClick = { onCopy(value) }) { Text("复制链接") } }, dismissButton = { TextButton(onClick = { qr = null }) { Text("关闭") } }) }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("使用与权限") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("设备连接同一个 Wi-Fi。开启共享后，iPhone 用相机扫码并在 Safari 打开；另一台 Android 在“连接”中扫码。", fontSize = 14.sp); Text("使用可信局域网：HTTP 传输未加密。二维码链接授予访问权限，请仅与接收方分享。停止共享会撤销当前连接。", fontSize = 14.sp); Text("其他设备上传到本机的文件先临时存放，连接本机的第三台设备可直接下载。只有本机点击“保存”，才会保存到“下载 / FileFlash”。临时文件会按缓存规则清理，请及时转发或保存。", fontSize = 14.sp); Text("在“连接”页面点击“接收”，会将所选文件保存到本机。网页中断后需重新选择文件，从零发送；大文件传输保持前台。", fontSize = 14.sp); Text("系统可能限制长时间后台传输。网络改变后重新扫码；访客 Wi-Fi 可能禁止设备互通。", fontSize = 14.sp) } }, confirmButton = { TextButton(onClick = { showInfo = false }) { Text("关闭") } }, dismissButton = { TextButton(onClick = onSettings) { Text("系统设置") } })
}

@Composable private fun HostReceiveProgress(feedback: HostReceiveFeedback) {
    val failed = feedback.phase == "接收中断" || feedback.phase == "失败"
    val title = if (feedback.fileCount > 1) "${feedback.fileCount} 个文件" else feedback.name
    val total = feedback.totalBytes
    val progress = total?.let { if (it == 0L) 0f else (feedback.receivedBytes.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
    Card(
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = acid.surface), shape = Corner
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(feedback.phase, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (failed) Error else Blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            val bar = Modifier.fillMaxWidth().semantics { contentDescription = "接收进度" }
            if (feedback.active && progress == null) LinearProgressIndicator(modifier = bar)
            else LinearProgressIndicator(progress = { if (feedback.phase == "已接收") 1f else progress ?: 0f }, modifier = bar, color = if (failed) Error else Blue)
            val detail = buildString {
                append(bytes(feedback.receivedBytes))
                if (feedback.active && total != null) {
                    append(" / ").append(bytes(total))
                    append(" · ").append(if (total == 0L) 0 else ((feedback.receivedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0) * 100).toInt()).append('%')
                }
                if (feedback.active && feedback.speedBytesPerSecond > 0L) append(" · ").append(bytes(feedback.speedBytesPerSecond)).append("/s")
            }
            Text(detail, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable private fun HostPage(state: AppState, repository: TransferRepository, start: () -> Unit, pick: () -> Unit, onQr: (String) -> Unit, copy: (String) -> Unit) {
    var discard by remember { mutableStateOf<RemoteFile?>(null) }
    val temporaryIds = state.hostTemporary.map { it.id }.toSet()
    val sharedFiles = state.hostState.files.filterNot { it.id in temporaryIds }
    discard?.let { file -> DiscardHostFileDialog(file, onDismiss = { discard = null }, onDiscard = { discard = null; repository.discardHostFile(file.id) }) }
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item { AcidPoster("01", "共享", "FILE", "FLASH.", if (state.hostRunning) "本机服务器 / 已开启" else "", compact = state.hostRunning, backdrop = acid.surface) }
        item {
            SurfaceBlock {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("本机共享", fontSize = 12.sp, color = Muted)
                        Text(when { state.hostRunning -> "共享已开启"; state.hostStarting -> "正在开启"; state.hostStopping -> "正在停止"; else -> "准备传送" }, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    }
                    if (state.hostStarting || state.hostStopping) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    else if (state.hostRunning) OutlinedButton(onClick = { repository.stopHost() }, shape = Corner, modifier = Modifier.heightIn(min = 52.dp)) { AcidActionLabel("停止", R.drawable.ic_acid_square) }
                    else AcidIcon(R.drawable.ic_acid_radio_tower, modifier = Modifier.size(36.dp), tint = Ink)
                }
                if (!state.hostRunning && !state.hostStarting && !state.hostStopping) {
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = start, shape = Corner, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
                        AcidActionLabel("开启共享", R.drawable.ic_acid_power, large = true)
                        Spacer(Modifier.weight(1f))
                        AcidIcon(R.drawable.ic_acid_arrow_up_right)
                    }
                }
            }
        }
        if (state.hostRunning) {
            item {
                SurfaceBlock {
                    if (state.addresses.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            QrImage(state.addresses.first(), 164)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("SCAN\nTO JOIN", fontFamily = AcidDisplay, fontSize = 28.sp, lineHeight = 31.sp)
                                Text("扫码连接", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text("${state.hostState.clients.size} 台已配对", fontFamily = AcidMono, fontSize = 12.sp, color = Muted)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        state.addresses.forEach { address ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SelectionContainer(Modifier.weight(1f)) { Text(address.substringBefore("/#"), fontFamily = AcidMono, fontSize = 12.sp) }
                                androidx.compose.material3.IconButton(onClick = { onQr(address) }) { AcidIcon(R.drawable.ic_acid_qr_code, "显示二维码") }
                            }
                        }
                    } else Text("未找到局域网地址，请连接 Wi-Fi 后重试", color = Error, fontSize = 14.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { state.addresses.firstOrNull()?.let(copy) }, enabled = state.addresses.isNotEmpty(), shape = Corner, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 12.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { AcidActionLabel("复制", R.drawable.ic_acid_copy) }
                        Button(onClick = pick, shape = Corner, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp), modifier = Modifier.weight(1.4f).heightIn(min = 52.dp)) { AcidActionLabel("添加文件", R.drawable.ic_acid_plus) }
                    }
                }
            }
        }
        if (state.hostTemporary.isNotEmpty()) {
            item { SectionTitle("临时文件", state.hostTemporary.size.toString()) }
            items(state.hostTemporary, key = { "temp-${it.id}" }) { file -> HostTemporaryRow(file, repository, state.tasks.firstOrNull { it.id == "save-${file.id}" }, state.hostRunning, onSave = { repository.saveHostFile(file.id) }, onDiscard = { discard = file }) }
        }
        if (state.hostRunning) {
            if (sharedFiles.isNotEmpty() || state.hostTemporary.isEmpty()) item { SectionTitle("共享文件", sharedFiles.size.toString()) }
            if (sharedFiles.isEmpty() && state.hostTemporary.isEmpty()) item { EmptyRow("暂无共享文件") }
            items(sharedFiles, key = { "share-${it.id}" }) { file -> FileRow(file.name, "${bytes(file.size)}${if (file.received) " · 已保存" else ""}", preview = { FilePreview(file, repository, remote = false) }) { TextButton(onClick = { repository.removeShared(file.id, file.name) }) { Text("移除") } } }
            if (state.hostState.clients.isNotEmpty()) {
                item { SectionTitle("已配对设备", state.hostState.clients.size.toString()) }
                items(state.hostState.clients, key = { "client-${it.id}" }) { client -> FileRow(client.name, "已配对") { TextButton(onClick = { repository.revoke(client.id) }) { Text("断开") } } }
            }
        }
    }
}

@Composable private fun RemotePage(state: AppState, repository: TransferRepository, address: String, setAddress: (String) -> Unit, token: String, setToken: (String) -> Unit, scan: () -> Unit, connect: (String, String) -> Unit, upload: () -> Unit, endpoint: String) {
    val connectionFault = !state.remoteError.isNullOrBlank()
    val listState = rememberLazyListState()
    val openedAt = rememberSaveable { System.currentTimeMillis() }
    var focusedFile by remember { mutableStateOf<String?>(null) }
    val receives = state.tasks.filter { it.direction == "接收" && it.endpoint == endpoint && it.remote != null }.distinctBy { it.remote!!.id }
    val byFile = receives.associateBy { it.remote!!.id }
    val sharedIds = state.remoteState.files.map { it.id }.toSet()
    val detached = receives.filter { it.remote!!.id !in sharedIds && (it.status != "完成" || it.updatedAt >= openedAt) }
    fun focus(file: RemoteFile, action: () -> Unit) { focusedFile = file.id; action() }
    LaunchedEffect(focusedFile, byFile[focusedFile]?.id) {
        val index = state.remoteState.files.indexOfFirst { it.id == focusedFile }
        if (index >= 0 && byFile[focusedFile] != null) {
            // Reveal after the receive card has expanded and the list has measured it.
            withFrameNanos { }
            withFrameNanos { }
            listState.scrollToItem(3 + (if (detached.isEmpty()) 0 else 1 + detached.size) + index)
        }
    }
    @Composable fun receive(file: RemoteFile, task: NativeTask?) {
        val saved = task?.let { current -> state.saved.firstOrNull { it.id == current.id } ?: current.savedUri?.let { SavedFile(current.id, current.name, current.size, file.mime, it) } }
        ReceiveFileRow(file, repository, task, saved, state.remoteConnected && state.remoteAddress == task?.endpoint.orEmpty().ifBlank { endpoint },
            onReceive = { focus(file) { repository.download(file) } },
            onRetry = { task?.let { focus(file) { repository.retry(it) } } },
            onPause = { task?.let { repository.cancelTask(it.id) } },
            onOpen = { saved?.let { repository.openSaved(it) } })
    }
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
        item { AcidPoster("02", "连接", "LINK", "UP.", when { state.remotePairingExpired -> "配对已失效"; connectionFault && state.remoteConnected -> "连接中断"; connectionFault -> "配对失败"; state.remoteConnected -> "连接至其他设备"; else -> "扫码 / 配对码" }, compact = true, disrupted = connectionFault, backdrop = acid.surface) }
        item { SurfaceBlock {
            if (connectionFault) {
                Row(Modifier.fillMaxWidth().background(Error.copy(alpha = .08f)).padding(12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                    AcidIcon(R.drawable.ic_acid_circle_alert, tint = Error)
                    Spacer(Modifier.width(10.dp))
                    Text(state.remoteError.orEmpty(), color = Error, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
            if (!state.remoteConnected) {
                Button(onClick = scan, shape = Corner, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { AcidActionLabel("扫码连接", R.drawable.ic_acid_scan_line, large = true) }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = address, onValueChange = setAddress, label = { Text("连接链接") }, singleLine = true, shape = Corner, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = token, onValueChange = setToken, label = { Text("配对码（完整链接可留空）") }, singleLine = true, shape = Corner, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { connect(address, token) }, enabled = address.isNotBlank() && !state.remoteConnecting, modifier = Modifier.fillMaxWidth(), shape = Corner) { if (state.remoteConnecting) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)) }; Text(if (state.remoteConnecting) "连接中" else "连接") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(if (state.remoteError == null) "已连接" else "连接中断", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(state.remoteAddress, fontSize = 13.sp, color = Muted) }; TextButton(onClick = repository::disconnect) { Text("断开") } }
                Spacer(Modifier.height(12.dp))
                Button(onClick = upload, shape = Corner, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { AcidActionLabel("发送文件", R.drawable.ic_acid_upload) }
            }
        } }
        if (detached.isNotEmpty()) {
            item { SectionTitle("接收", detached.size.toString()) }
            items(detached, key = { "receive-${it.id}" }) { task -> receive(task.remote!!, task) }
        }
        if (state.remoteConnected) {
            item { SectionTitle("对方共享", state.remoteState.files.size.toString()) }
            if (state.remoteState.files.isEmpty()) item { EmptyRow("对方尚未添加文件") }
            items(state.remoteState.files, key = { it.id }) { file -> receive(file, byFile[file.id]) }
        }
    }
}

@Composable private fun ReceiveFileRow(file: RemoteFile, repository: TransferRepository, task: NativeTask?, saved: SavedFile?, connected: Boolean, onReceive: () -> Unit, onRetry: () -> Unit, onPause: () -> Unit, onOpen: () -> Unit) {
    SurfaceBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val previewFile = saved?.let { RemoteFile(id = it.id, name = it.name, size = it.size, mime = it.mime, createdAt = it.createdAt) } ?: file
            FilePreview(previewFile, repository, remote = saved == null)
            Column(Modifier.weight(1f)) {
                Text(file.name, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${bytes(file.size)}${if (file.temporary) " · 临时中转" else ""}", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (task != null) {
            val label = when (task.status) { "等待" -> "排队中"; "传输中" -> "下载中"; "完成" -> "已保存"; "失败" -> "下载失败"; else -> task.status }
            Text(label, fontSize = 14.sp, color = if (task.status == "失败") Error else if (task.status == "完成") Blue else Ink,
                modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
            if (task.status != "完成") {
                Spacer(Modifier.height(8.dp))
                val progressModifier = Modifier.fillMaxWidth().semantics { contentDescription = "接收进度" }
                if (task.status in listOf("等待", "连接中", "校验中", "保存中")) LinearProgressIndicator(modifier = progressModifier)
                else LinearProgressIndicator(progress = { if (task.size > 0) (task.transferred.toDouble() / task.size).toFloat().coerceIn(0f, 1f) else 0f }, modifier = progressModifier)
                val percent = if (task.size > 0) (task.transferred.toDouble() / task.size * 100).toInt().coerceIn(0, 100) else 0
                Text("${bytes(task.transferred)} / ${bytes(task.size)} · $percent%${if (task.speed > 0 && task.status == "传输中") " · ${bytes(task.speed)}/s" else ""}", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
            task.error?.takeIf { it.isNotBlank() && task.status == "失败" }?.let { Text(it, fontSize = 13.sp, color = Error, modifier = Modifier.padding(top = 6.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            when (task?.status) {
                null -> Button(onClick = onReceive, enabled = connected, shape = Corner) { AcidActionLabel("接收", R.drawable.ic_acid_download) }
                "完成" -> {
                    TextButton(onClick = onReceive, enabled = connected) { Text("重新接收") }
                    if (saved != null) Button(onClick = onOpen, shape = Corner) { AcidActionLabel("打开", R.drawable.ic_acid_external_link) }
                }
                "失败", "已暂停", "已取消" -> Button(onClick = onRetry, enabled = connected, shape = Corner) { AcidActionLabel("重试", R.drawable.ic_acid_rotate_cw) }
                else -> TextButton(onClick = onPause) { Text("暂停") }
            }
        }
    }
}

@Composable private fun TextPage(state: AppState, repository: TransferRepository, copy: (String) -> Unit) {
    var remote by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val entries = if (remote) state.remoteState.texts else state.hostState.texts
    val available = if (remote) state.remoteConnected else state.hostRunning
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item { AcidPoster("03", "文字", "TEXT", "DROP.", "文字 / 链接 / 剪贴板", compact = true) }
        item { TabRow(selectedTabIndex = if (remote) 1 else 0, containerColor = Background) { Tab(selected = !remote, onClick = { remote = false }, text = { Text("本机共享") }); Tab(selected = remote, onClick = { remote = true }, text = { Text("已连接设备") }) } }
        item { SurfaceBlock { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("输入或粘贴文字") }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth(), shape = Corner); Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { if (!available) Text(if (remote) "请先连接设备" else "请先开启共享", fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f)); Button(onClick = { val sent = text; sending = true; repository.sendText(sent, remote) { success -> sending = false; if (success && text == sent) text = "" } }, enabled = available && text.isNotBlank() && !sending, shape = Corner) { Text(if (sending) "发送中" else "发送") } } } }
        if (entries.isEmpty()) item { EmptyRow("暂无文字") }
        items(entries.reversed(), key = { it.id }) { entry -> SurfaceBlock { Row(verticalAlignment = Alignment.CenterVertically) { Text(entry.sender.ifBlank { "设备" }, modifier = Modifier.weight(1f), color = Muted, fontSize = 13.sp); TextButton(onClick = { copy(entry.text) }) { Text("复制") } }; SelectionContainer { Text(entry.text, fontSize = 15.sp, lineHeight = 23.sp) } } }
    }
}

@Composable private fun HistoryPage(state: AppState, repository: TransferRepository) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var clearCache by remember { mutableStateOf(false) }
    var cachePolicy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf<NativeTask?>(null) }
    var discardHost by remember { mutableStateOf<RemoteFile?>(null) }
    val temporaryIds = state.hostTemporary.map { it.id }.toSet()
    val visibleTasks = state.tasks.filterNot { it.id.startsWith("save-") && it.id.removePrefix("save-") in temporaryIds }
    val hostUploads = state.hostState.uploads.filterNot { it.status == "complete" && it.id in temporaryIds }
    LaunchedEffect(Unit) { repository.refreshStorageInfo() }
    if (cachePolicy) AlertDialog(onDismissRequest = { cachePolicy = false }, title = { Text("临时文件") }, text = { Text("无缩略图磁盘缓存。临时中转文件和闲置续传数据保留 24 小时，以 256 MB 为闲置清理目标；超过目标时优先清理闲置超过 30 分钟的数据。正在传输、下载或保存，以及保存失败待重试和旧版待保存的文件会保留。") }, confirmButton = { TextButton(onClick = { cachePolicy = false }) { Text("关闭") } })
    if (clearCache) AlertDialog(onDismissRequest = { clearCache = false }, title = { Text("清理临时文件？") }, text = { Text("将删除未在使用的临时中转文件，以及已暂停或失败任务的续传数据。删除后其他设备无法再下载这些文件，需要重新上传。\n\n正在传输、下载或保存的文件、保存失败待重试的文件和旧版待保存文件会保留。已保存到“下载 / FileFlash”的文件不受影响。") }, confirmButton = { TextButton(onClick = { clearCache = false; repository.clearReceivedCache() }) { Text("清理") } }, dismissButton = { TextButton(onClick = { clearCache = false }) { Text("保留") } })
    discard?.let { task -> AlertDialog(onDismissRequest = { discard = null }, title = { Text("丢弃未完成任务？") }, text = { Text("${task.name}\n\n删除此任务的临时数据，之后需要重新传输。原文件和已保存文件会保留。") }, confirmButton = { TextButton(onClick = { discard = null; repository.discardTask(task) }) { Text("丢弃") } }, dismissButton = { TextButton(onClick = { discard = null }) { Text("保留") } }) }
    discardHost?.let { file -> DiscardHostFileDialog(file, onDismiss = { discardHost = null }, onDiscard = { discardHost = null; repository.discardHostFile(file.id) }) }
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item { AcidPoster("04", "记录", "LOG", "BOOK.", "传输任务 / 已保存文件", compact = true) }
        item { TabRow(selectedTabIndex = tab, containerColor = Background) { Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("任务") }); Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("已保存") }) } }
        item { SurfaceBlock {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("临时占用", fontSize = 12.sp, color = Muted); Text(bytes(state.temporaryBytes), fontFamily = AcidDisplay, fontSize = 32.sp); Text("可清理 ${bytes(state.reclaimableBytes)}", fontFamily = AcidMono, fontSize = 12.sp, color = Muted) }
                androidx.compose.material3.IconButton(onClick = { cachePolicy = true }) { AcidIcon(R.drawable.ic_acid_info, "清理规则") }
                OutlinedButton(onClick = { clearCache = true }, enabled = !state.cacheCleaning, shape = Corner) { AcidActionLabel(if (state.cacheCleaning) "清理中" else "清理", R.drawable.ic_acid_trash_2) }
            }
        } }
        if (tab == 1) {
            item { Text("下载 / FileFlash", color = Muted, fontSize = 13.sp) }
            if (state.saved.isEmpty()) item { EmptyRow("暂无已保存文件") }
            items(state.saved, key = { it.id }) { saved -> FileRow(saved.name, bytes(saved.size), preview = { FilePreview(RemoteFile(id = saved.id, name = saved.name, size = saved.size, mime = saved.mime, createdAt = saved.createdAt), repository, remote = false) }) { TextButton(onClick = { repository.openSaved(saved) }) { AcidActionLabel("打开", R.drawable.ic_acid_external_link) } } }
        } else {
            if (state.hostTemporary.isNotEmpty()) item { SectionTitle("临时文件", state.hostTemporary.size.toString()) }
            items(state.hostTemporary, key = { "temp-${it.id}" }) { file -> HostTemporaryRow(file, repository, state.tasks.firstOrNull { it.id == "save-${file.id}" }, state.hostRunning, onSave = { repository.saveHostFile(file.id) }, onDiscard = { discardHost = file }) }
            if (state.hostTemporary.isEmpty() && visibleTasks.isEmpty() && hostUploads.isEmpty() && state.hostState.downloads.isEmpty()) item { EmptyRow("暂无传输任务") }
            items(visibleTasks, key = { "task-${it.id}" }) { task ->
                TaskRow(task.name, task.direction, task.transferred, task.size, task.status, task.speed, task.error) {
                    when (task.status) {
                        "失败", "已暂停", "已取消" -> Column {
                            TextButton(onClick = { repository.retry(task) }, enabled = when (task.direction) { "共享" -> state.hostRunning; "保存" -> true; else -> state.remoteConnected }) { AcidActionLabel("重试", R.drawable.ic_acid_rotate_cw) }
                            TextButton(onClick = { discard = task }) { Text("丢弃") }
                        }
                        "完成" -> if (task.savedUri != null) TextButton(onClick = { repository.openSaved(SavedFile(task.id, task.name, task.size, task.remote?.mime ?: "application/octet-stream", task.savedUri)) }) { AcidActionLabel("打开", R.drawable.ic_acid_external_link) }
                        else -> TextButton(onClick = { repository.cancelTask(task.id) }) { Text("暂停") }
                    }
                }
            }
            items(hostUploads.sortedByDescending { it.createdAt }, key = { "hostup-${it.id}" }) { task -> TaskRow(task.name, "接收", task.offset, task.size, hostStatus(task.status), task.speed, task.error, lengthKnown = task.lengthKnown) { if (task.status in listOf("uploading", "failed")) TextButton(onClick = { repository.cancelHostUpload(task.id) }) { Text("取消") } } }
            items(state.hostState.downloads, key = { "hostdown-${it.id}" }) { task -> TaskRow(task.name, "发送", task.transferred, task.size, hostStatus(task.status), task.speed, task.error) { if (task.status == "downloading") TextButton(onClick = { repository.cancelHostDownload(task.id) }) { Text("取消") } } }
        }
    }
}

@Composable private fun HostTemporaryRow(file: RemoteFile, repository: TransferRepository, saveTask: NativeTask?, hostRunning: Boolean, onSave: () -> Unit, onDiscard: () -> Unit) {
    val saving = saveTask?.status == "保存中"
    val saveFailed = saveTask?.status in listOf("失败", "已暂停", "已取消")
    SurfaceBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilePreview(file, repository, remote = false)
            Column(Modifier.weight(1f)) {
                Text(file.name, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("${bytes(file.size)} · ${if (file.temporary) "临时中转" else "待保存"}", color = Muted, fontSize = 13.sp)
            }
        }
        Text(if (hostRunning) "连接本机的其他设备可下载；点击“保存”后才进入本机下载目录。" else "重新开启共享后，其他设备可下载；点击“保存”可存入本机下载目录。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        if (saving) {
            LinearProgressIndicator(progress = { if (file.size > 0) ((saveTask?.transferred ?: 0).toDouble() / file.size).toFloat().coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        if (saveFailed) Text("保存失败${saveTask?.error?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "，可重试"}。文件已保留，等待重试。", color = Error, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDiscard, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("丢弃") }
            OutlinedButton(onClick = onSave, enabled = !saving, shape = Corner, modifier = Modifier.heightIn(min = 48.dp)) {
                if (saving) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)) }
                Text(if (saving) "保存中" else if (saveFailed) "重试保存" else "保存")
            }
        }
    }
}

@Composable private fun DiscardHostFileDialog(file: RemoteFile, onDismiss: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("丢弃临时文件？") }, text = { Text("${file.name}\n\n将删除本机这份临时文件，其他设备将无法再从本机下载，需要重新上传。原文件和已保存文件不受影响。") }, confirmButton = { TextButton(onClick = onDiscard) { Text("丢弃") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("保留") } })
}

@Composable private fun SurfaceBlock(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(acid.surface)) {
        Column(Modifier.padding(18.dp), content = content)
        HorizontalDivider(color = Border)
    }
}
@Composable private fun SectionTitle(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().background(Ink).padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Background, modifier = Modifier.weight(1f))
        detail?.let { Text(it.padStart(2, '0'), fontFamily = AcidDisplay, color = Background, fontSize = 26.sp) }
    }
}
@Composable private fun EmptyRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AcidIcon(R.drawable.ic_acid_folder_open, modifier = Modifier.size(30.dp), tint = Muted)
        Text(text, color = Muted, fontSize = 16.sp)
    }
}
@Composable private fun FileRow(name: String, detail: String, preview: (@Composable () -> Unit)? = null, action: @Composable () -> Unit) {
    SurfaceBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (preview != null) preview() else AcidIcon(R.drawable.ic_acid_files, tint = Muted)
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(detail, fontFamily = AcidMono, color = Muted, fontSize = 12.sp)
            }
            action()
        }
    }
}
@Composable private fun TaskRow(name: String, direction: String, done: Long, total: Long, status: String, speed: Long, error: String?, lengthKnown: Boolean = true, action: @Composable () -> Unit) { SurfaceBlock { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(name, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("$direction · $status", fontSize = 13.sp, color = if (status == "失败") Error else Muted) }; action() }; Spacer(Modifier.height(8.dp)); if (status !in listOf("完成", "失败", "已暂停", "已取消")) { if (lengthKnown) LinearProgressIndicator(progress = { if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }; Spacer(Modifier.height(6.dp)); Text("${bytes(done)}${if (lengthKnown) " / ${bytes(total)}" else ""}${if (speed > 0) " · ${bytes(speed)}/s" else ""}", color = Muted, fontSize = 12.sp); error?.takeIf { it.isNotBlank() }?.let { Text(it, color = Error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) } } }
private fun hostStatus(status: String) = when (status) { "complete" -> "完成"; "uploading", "downloading", "transferring", "active" -> "传输中"; "verifying" -> "校验中"; "failed" -> "失败"; "cancelled" -> "已取消"; else -> status }
@Composable private fun QrImage(value: String, size: Int = 224) {
    val displaySize = minOf(size, (LocalConfiguration.current.screenHeightDp * 0.4f).toInt().coerceAtLeast(100))
    val bitmap = remember(value) { runCatching { val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 640, 640, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2)); Bitmap.createBitmap(640, 640, Bitmap.Config.RGB_565).apply { val pixels = IntArray(640 * 640) { i -> if (matrix[i % 640, i / 640]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }; setPixels(pixels, 0, 640, 0, 0, 640, 640) } }.getOrNull() }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = "配对二维码", modifier = Modifier.size(displaySize.dp).background(Color.White)) }
}

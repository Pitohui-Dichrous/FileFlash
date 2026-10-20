package app.fileflash

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
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

private val Background = Color(0xFFF5F6F8)
private val Ink = Color(0xFF16202A)
private val Blue = Color(0xFF2667E8)
private val Muted = Color(0xFF526170)
private val Border = Color(0xFFD8DFE7)
private val Error = Color(0xFFAF2F33)
private val Corner = RoundedCornerShape(12.dp)

class MainActivity : ComponentActivity() {
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
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, onPrimary = Color.White, secondaryContainer = Color(0xFFE8EFFF), onSecondaryContainer = Blue, background = Background, onBackground = Ink, surface = Color.White, onSurface = Ink, onSurfaceVariant = Muted, outline = Border, error = Error), shapes = androidx.compose.material3.Shapes(small = Corner, medium = Corner, large = Corner)) {
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

    private fun withNetwork(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(this, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            pendingNetwork = action; networkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
        } else action()
    }
    private fun withNotification(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) action()
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
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); repository.notice(null) } }
    LaunchedEffect(scanned) { if (scanned != null) { address = scanned; page = 1; onConnect(scanned, ""); onScanned() } }
    val tabs = listOf("共享", "连接", "文字", "记录")
    Scaffold(containerColor = Background,
        topBar = { TopAppBar(title = { Text("FileFlash", fontSize = 21.sp, fontWeight = FontWeight.SemiBold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Background), actions = { TextButton(onClick = { showInfo = true }) { Text("帮助") } }) },
        bottomBar = { NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) { tabs.forEachIndexed { index, title -> NavigationBarItem(selected = page == index, onClick = { page = index }, icon = { NavGlyph(index, page == index) }, label = { Text(title) }) } } },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when (page) {
                0 -> HostPage(state, repository, onStart, onPickFiles, onQr = { qr = it }, onCopy)
                1 -> RemotePage(state, repository, address, { address = it }, token, { token = it }, onScan, onConnect, onUpload)
                2 -> TextPage(state, repository, onCopy)
                3 -> HistoryPage(state, repository)
            }
        }
    }
    qr?.let { value -> AlertDialog(onDismissRequest = { qr = null }, title = { Text("扫码连接") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { QrImage(value); Spacer(Modifier.height(12.dp)); SelectionContainer { Text(value.substringBefore("/#"), fontSize = 14.sp) } } }, confirmButton = { TextButton(onClick = { onCopy(value) }) { Text("复制链接") } }, dismissButton = { TextButton(onClick = { qr = null }) { Text("关闭") } }) }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("使用与权限") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("两台设备连接同一个 Wi-Fi。开启共享后，iPhone 用相机扫码并在 Safari 打开；另一台 Android 在“连接”中扫码。", fontSize = 14.sp); Text("使用可信局域网：HTTP 传输未加密。二维码链接授予访问权限，请仅与接收方分享。停止共享会撤销当前连接。", fontSize = 14.sp); Text("收到的文件保存在“下载 / FileFlash”。Safari 上传期间保持页面在前台；页面重载后需重新选择原文件才能续传。", fontSize = 14.sp); Text("系统可能限制长时间后台传输。网络改变后重新扫码；访客 Wi-Fi 可能禁止设备互通。", fontSize = 14.sp) } }, confirmButton = { TextButton(onClick = { showInfo = false }) { Text("关闭") } }, dismissButton = { TextButton(onClick = onSettings) { Text("系统设置") } })
}

@Composable private fun NavGlyph(index: Int, selected: Boolean) {
    val color = if (selected) Blue else Muted
    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
        val s = size.width; val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx())
        when (index) {
            0 -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(s * .12f, s * .22f), androidx.compose.ui.geometry.Size(s * .76f, s * .64f), androidx.compose.ui.geometry.CornerRadius(s * .08f), style = stroke); drawLine(color, androidx.compose.ui.geometry.Offset(s * .34f, s * .22f), androidx.compose.ui.geometry.Offset(s * .34f, s * .1f), 1.8.dp.toPx()); drawLine(color, androidx.compose.ui.geometry.Offset(s * .34f, s * .1f), androidx.compose.ui.geometry.Offset(s * .7f, s * .1f), 1.8.dp.toPx()) }
            1 -> { listOf(.12f to .12f, .6f to .12f, .12f to .6f).forEach { (x,y) -> drawRect(color, androidx.compose.ui.geometry.Offset(s*x,s*y), androidx.compose.ui.geometry.Size(s*.27f,s*.27f), style = stroke) }; drawRect(color, androidx.compose.ui.geometry.Offset(s*.6f,s*.6f), androidx.compose.ui.geometry.Size(s*.27f,s*.27f)) }
            2 -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(s*.1f,s*.15f), androidx.compose.ui.geometry.Size(s*.8f,s*.62f), androidx.compose.ui.geometry.CornerRadius(s*.08f), style=stroke); drawLine(color, androidx.compose.ui.geometry.Offset(s*.25f,s*.35f), androidx.compose.ui.geometry.Offset(s*.75f,s*.35f),1.8.dp.toPx()); drawLine(color, androidx.compose.ui.geometry.Offset(s*.25f,s*.55f), androidx.compose.ui.geometry.Offset(s*.6f,s*.55f),1.8.dp.toPx()) }
            else -> { drawCircle(color,s*.37f,style=stroke); drawLine(color, androidx.compose.ui.geometry.Offset(s*.5f,s*.25f), androidx.compose.ui.geometry.Offset(s*.5f,s*.5f),1.8.dp.toPx()); drawLine(color, androidx.compose.ui.geometry.Offset(s*.5f,s*.5f), androidx.compose.ui.geometry.Offset(s*.7f,s*.58f),1.8.dp.toPx()) }
        }
    }
}

@Composable private fun HostPage(state: AppState, repository: TransferRepository, start: () -> Unit, pick: () -> Unit, onQr: (String) -> Unit, copy: (String) -> Unit) {
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SurfaceBlock {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { Text(if (state.hostRunning) "共享已开启" else if (state.hostStarting) "正在开启" else if (state.hostStopping) "正在停止" else "本机共享", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); if (state.hostRunning) Text("${state.hostState.clients.size} 台设备已配对", color = Muted, fontSize = 13.sp) }
                    if (state.hostStarting || state.hostStopping) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else if (state.hostRunning) OutlinedButton(onClick = repository::stopHost, shape = Corner) { Text("停止") }
                    else Button(onClick = start, shape = Corner) { Text("开启") }
                }
                if (state.hostRunning) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Border)
                    if (state.addresses.isEmpty()) Text("未找到局域网地址，请连接 Wi-Fi 后重试", color = Error, fontSize = 14.sp)
                    else state.addresses.forEach { address ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer(Modifier.weight(1f)) { Text(address.substringBefore("/#"), fontSize = 14.sp) }
                            TextButton(onClick = { onQr(address) }) { Text("二维码") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { state.addresses.firstOrNull()?.let(copy) }, enabled = state.addresses.isNotEmpty(), shape = Corner) { Text("复制链接") }; Button(onClick = pick, shape = Corner) { Text("添加文件") } }
                }
            }
        }
        if (state.hostRunning && state.addresses.isNotEmpty()) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(state.addresses.first(), 188) } }
        if (state.hostRunning) {
            item { SectionTitle("共享文件", state.hostState.files.size.toString()) }
            if (state.hostState.files.isEmpty()) item { EmptyRow("暂无共享文件") }
            items(state.hostState.files, key = { "share-${it.id}" }) { file -> FileRow(file.name, "${bytes(file.size)}${if (file.received) " · 已接收" else ""}") { TextButton(onClick = { repository.removeShared(file.id, file.name) }) { Text("移除") } } }
            if (state.hostState.clients.isNotEmpty()) {
                item { SectionTitle("已配对设备") }
                items(state.hostState.clients, key = { "client-${it.id}" }) { client -> FileRow(client.name, "已配对") { TextButton(onClick = { repository.revoke(client.id) }) { Text("断开") } } }
            }
        }
    }
}

@Composable private fun RemotePage(state: AppState, repository: TransferRepository, address: String, setAddress: (String) -> Unit, token: String, setToken: (String) -> Unit, scan: () -> Unit, connect: (String, String) -> Unit, upload: () -> Unit) {
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().imePadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SurfaceBlock {
            if (!state.remoteConnected) {
                Button(onClick = scan, shape = Corner, modifier = Modifier.fillMaxWidth()) { Text("扫码连接") }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = address, onValueChange = setAddress, label = { Text("连接链接") }, singleLine = true, shape = Corner, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = token, onValueChange = setToken, label = { Text("配对码（完整链接可留空）") }, singleLine = true, shape = Corner, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { connect(address, token) }, enabled = address.isNotBlank() && !state.remoteConnecting, modifier = Modifier.fillMaxWidth(), shape = Corner) { if (state.remoteConnecting) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)) }; Text(if (state.remoteConnecting) "连接中" else "连接") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(if (state.remoteError == null) "已连接" else "连接中断", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(state.remoteAddress, fontSize = 13.sp, color = Muted) }; TextButton(onClick = repository::disconnect) { Text("断开") } }
                Spacer(Modifier.height(12.dp))
                Button(onClick = upload, shape = Corner, modifier = Modifier.fillMaxWidth()) { Text("发送文件") }
            }
            state.remoteError?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Error, fontSize = 14.sp) }
        } }
        if (state.remoteConnected) {
            item { SectionTitle("对方共享", state.remoteState.files.size.toString()) }
            if (state.remoteState.files.isEmpty()) item { EmptyRow("对方尚未添加文件") }
            items(state.remoteState.files, key = { it.id }) { file -> FileRow(file.name, bytes(file.size)) { Button(onClick = { repository.download(file) }, shape = Corner) { Text("接收") } } }
        }
    }
}

@Composable private fun TextPage(state: AppState, repository: TransferRepository, copy: (String) -> Unit) {
    var remote by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val entries = if (remote) state.remoteState.texts else state.hostState.texts
    val available = if (remote) state.remoteConnected else state.hostRunning
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().imePadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TabRow(selectedTabIndex = if (remote) 1 else 0, containerColor = Background) { Tab(selected = !remote, onClick = { remote = false }, text = { Text("本机共享") }); Tab(selected = remote, onClick = { remote = true }, text = { Text("已连接设备") }) } }
        item { SurfaceBlock { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("输入或粘贴文字") }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth(), shape = Corner); Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { if (!available) Text(if (remote) "请先连接设备" else "请先开启共享", fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f)); Button(onClick = { val sent = text; sending = true; repository.sendText(sent, remote) { success -> sending = false; if (success && text == sent) text = "" } }, enabled = available && text.isNotBlank() && !sending, shape = Corner) { Text(if (sending) "发送中" else "发送") } } } }
        if (entries.isEmpty()) item { EmptyRow("暂无文字") }
        items(entries.reversed(), key = { it.id }) { entry -> SurfaceBlock { Row(verticalAlignment = Alignment.CenterVertically) { Text(entry.sender.ifBlank { "设备" }, modifier = Modifier.weight(1f), color = Muted, fontSize = 13.sp); TextButton(onClick = { copy(entry.text) }) { Text("复制") } }; SelectionContainer { Text(entry.text, fontSize = 15.sp, lineHeight = 23.sp) } } }
    }
}

@Composable private fun HistoryPage(state: AppState, repository: TransferRepository) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var clearCache by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf<NativeTask?>(null) }
    LaunchedEffect(Unit) { repository.refreshStorageInfo() }
    if (clearCache) AlertDialog(onDismissRequest = { clearCache = false }, title = { Text("清理临时文件？") }, text = { Text("将清理已暂停或失败任务的续传数据，之后需要重新传输。已保存到“下载 / FileFlash”的文件、等待保存的完整文件和进行中的传输会保留。") }, confirmButton = { TextButton(onClick = { clearCache = false; repository.clearReceivedCache() }) { Text("清理") } }, dismissButton = { TextButton(onClick = { clearCache = false }) { Text("保留") } })
    discard?.let { task -> AlertDialog(onDismissRequest = { discard = null }, title = { Text("丢弃未完成任务？") }, text = { Text("${task.name}\n\n删除此任务的临时数据，之后需要重新传输。原文件和已保存文件会保留。") }, confirmButton = { TextButton(onClick = { discard = null; repository.discardTask(task) }) { Text("丢弃") } }, dismissButton = { TextButton(onClick = { discard = null }) { Text("保留") } }) }
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TabRow(selectedTabIndex = tab, containerColor = Background) { Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("任务") }); Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("已接收") }) } }
        item { SurfaceBlock {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("临时文件 ${bytes(state.temporaryBytes)}", fontSize = 14.sp); Text("可清理 ${bytes(state.reclaimableBytes)}", fontSize = 12.sp, color = Muted) }
                TextButton(onClick = { clearCache = true }, enabled = !state.cacheCleaning) { Text(if (state.cacheCleaning) "清理中" else "清理") }
            }
            Text("无缩略图磁盘缓存。闲置续传数据保留 24 小时，并以 256 MB 为清理目标；正在传输、最近 30 分钟的任务和等待保存的完整文件会保留。", fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
        } }
        if (tab == 1) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text("下载 / FileFlash", modifier = Modifier.weight(1f), color = Muted, fontSize = 13.sp); TextButton(onClick = repository::retrySaving) { Text("重试保存") } } }
            if (state.saved.isEmpty()) item { EmptyRow("暂无接收文件") }
            items(state.saved, key = { it.id }) { saved -> FileRow(saved.name, bytes(saved.size)) { TextButton(onClick = { repository.openSaved(saved) }) { Text("打开") } } }
        } else {
            if (state.tasks.isEmpty() && state.hostState.uploads.isEmpty() && state.hostState.downloads.isEmpty()) item { EmptyRow("暂无传输任务") }
            items(state.tasks, key = { "task-${it.id}" }) { task ->
                TaskRow(task.name, task.direction, task.transferred, task.size, task.status, task.speed, task.error) {
                    when (task.status) {
                        "失败", "已暂停", "已取消" -> Column {
                            TextButton(onClick = { repository.retry(task) }, enabled = when (task.direction) { "共享" -> state.hostRunning; "保存" -> true; else -> state.remoteConnected }) { Text("重试") }
                            TextButton(onClick = { discard = task }) { Text("丢弃") }
                        }
                        "完成" -> if (task.savedUri != null) TextButton(onClick = { repository.openSaved(SavedFile(task.id, task.name, task.size, task.remote?.mime ?: "application/octet-stream", task.savedUri)) }) { Text("打开") }
                        else -> TextButton(onClick = { repository.cancelTask(task.id) }) { Text("暂停") }
                    }
                }
            }
            items(state.hostState.uploads.sortedByDescending { it.createdAt }, key = { "hostup-${it.id}" }) { task -> TaskRow(task.name, "接收", task.offset, task.size, hostStatus(task.status), task.speed, task.error) { if (task.status in listOf("uploading", "failed")) TextButton(onClick = { repository.cancelHostUpload(task.id) }) { Text("取消") } } }
            items(state.hostState.downloads, key = { "hostdown-${it.id}" }) { task -> TaskRow(task.name, "发送", task.transferred, task.size, hostStatus(task.status), task.speed, task.error) { if (task.status == "downloading") TextButton(onClick = { repository.cancelHostDownload(task.id) }) { Text("取消") } } }
        }
    }
}

@Composable private fun SurfaceBlock(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth(), shape = Corner, colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(0.dp)) { Column(Modifier.padding(16.dp), content = content) } }
@Composable private fun SectionTitle(title: String, detail: String? = null) { Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f)); detail?.let { Text(it, color = Muted, fontSize = 13.sp) } } }
@Composable private fun EmptyRow(text: String) { Text(text, Modifier.padding(vertical = 20.dp, horizontal = 4.dp), color = Muted, fontSize = 14.sp) }
@Composable private fun FileRow(name: String, detail: String, action: @Composable () -> Unit) { SurfaceBlock { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Column(Modifier.weight(1f)) { Text(name, fontSize = 15.sp); Spacer(Modifier.height(4.dp)); Text(detail, color = Muted, fontSize = 13.sp) }; action() } } }
@Composable private fun TaskRow(name: String, direction: String, done: Long, total: Long, status: String, speed: Long, error: String?, action: @Composable () -> Unit) { SurfaceBlock { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(name, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("$direction · $status", fontSize = 13.sp, color = if (status == "失败") Error else Muted) }; action() }; Spacer(Modifier.height(8.dp)); if (status !in listOf("完成", "失败", "已暂停", "已取消")) LinearProgressIndicator(progress = { if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(6.dp)); Text("${bytes(done)} / ${bytes(total)}${if (speed > 0) " · ${bytes(speed)}/s" else ""}", color = Muted, fontSize = 12.sp); error?.takeIf { it.isNotBlank() }?.let { Text(it, color = Error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) } } }
private fun hostStatus(status: String) = when (status) { "complete" -> "完成"; "uploading", "downloading", "transferring", "active" -> "传输中"; "verifying" -> "校验中"; "failed" -> "失败"; "cancelled" -> "已取消"; else -> status }
@Composable private fun QrImage(value: String, size: Int = 224) {
    val displaySize = minOf(size, (LocalConfiguration.current.screenHeightDp * 0.4f).toInt().coerceAtLeast(100))
    val bitmap = remember(value) { runCatching { val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 640, 640, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2)); Bitmap.createBitmap(640, 640, Bitmap.Config.RGB_565).apply { val pixels = IntArray(640 * 640) { i -> if (matrix[i % 640, i / 640]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }; setPixels(pixels, 0, 640, 0, 0, 640, 640) } }.getOrNull() }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = "配对二维码", modifier = Modifier.size(displaySize.dp).background(Color.White)) }
}

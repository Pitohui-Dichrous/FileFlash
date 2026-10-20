package app.fileflash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class TransferService : Service() {
    private val repository get() = (application as FileFlashApplication).repository
    private val main = Handler(Looper.getMainLooper())
    private var lastUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "文件传输", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        repository.notificationChanged = {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastUpdate > 700) {
                lastUpdate = now
                main.post { runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification()) } }
            }
        }
        repository.serviceStopped = { main.post { if (!repository.hasWork()) stopSelf() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START_HOST -> repository.startHost()
            STOP -> { repository.stopEverything(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val state = repository.state.value
        val active = state.tasks.firstOrNull { it.status in listOf("传输中", "校验中", "连接中", "保存中", "校验续传文件", "校验源文件") }
        val hostUpload = state.hostState.uploads.firstOrNull { it.status == "uploading" || it.status == "verifying" }
        val hostDownload = state.hostState.downloads.firstOrNull { it.status in listOf("downloading", "transferring", "active") }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val name = active?.name ?: hostUpload?.name ?: hostDownload?.name
        val done = active?.transferred ?: hostUpload?.offset ?: hostDownload?.transferred ?: 0
        val total = active?.size ?: hostUpload?.size ?: hostDownload?.size ?: 0
        val text = if (name != null) "$name · ${bytes(done)} / ${bytes(total)}" else if (state.hostRunning) "等待连接 · ${state.hostState.clients.size} 台设备" else "准备传输"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(app.fileflash.R.drawable.ic_notification)
            .setContentTitle(active?.let { "${it.direction} · ${it.status}" } ?: if (name != null) "文件传输中" else "FileFlash 已开启")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "停止", stop)
            .apply { if (name != null) setProgress(1000, if (total > 0) (done.toDouble() / total * 1000).toInt().coerceIn(0, 1000) else 0, total == 0L) }
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        repository.stopEverything("系统已结束长时间后台传输；回到应用后可重新开启并续传")
        stopSelf()
    }

    override fun onDestroy() {
        repository.notificationChanged = null; repository.serviceStopped = null
        if (repository.hasWork()) repository.stopEverything()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "fileflash-transfer"
        const val NOTIFICATION = 1001
        const val START_HOST = "app.fileflash.START_HOST"
        const val STOP = "app.fileflash.STOP"
        fun startHost(context: Context) { ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java).setAction(START_HOST)) }
        fun ensure(context: Context) { ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java)) }
    }
}

package app.fileflash

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.FileOutputStream
import com.google.gson.Gson

data class LocalFile(val uri: String, val name: String, val size: Long, val mime: String)
data class SavedFile(val id: String, val name: String, val size: Long, val mime: String, val uri: String, val sha256: String? = null, val hostReceived: Boolean = false, val createdAt: Long = System.currentTimeMillis(), val hiddenFromHost: Boolean = false)

object Storage {
    private val downloadsPath = "${Environment.DIRECTORY_DOWNLOADS}/FileFlash"

    fun describe(context: Context, uri: Uri): LocalFile {
        var name = uri.lastPathSegment ?: "文件"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
        if (size < 0) throw IOException("无法确定文件大小，请从本机文件中选择")
        return LocalFile(uri.toString(), name, size, context.contentResolver.getType(uri) ?: "application/octet-stream")
    }

    fun stream(context: Context, item: LocalFile): InputStream = context.contentResolver.openInputStream(Uri.parse(item.uri)) ?: throw IOException("文件访问权限已失效，请重新选择")

    /** MediaStore reserves the final name and resolves duplicate names without overwriting. */
    fun publish(context: Context, id: String, file: File, name: String, mime: String, sha256: String? = null, hostReceived: Boolean = false, progress: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }): SavedFile {
        recoverPublications(context, cleanupPending = false).firstOrNull { it.id == id && isReadable(context, it) }?.let { return it }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, downloadsPath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("无法创建接收文件；请检查存储空间")
        val journal = context.getSharedPreferences("publication-journal", Context.MODE_PRIVATE)
        var saved = SavedFile(id, name, file.length(), mime, uri.toString(), sha256, hostReceived)
        try {
            // Commit before copying: process death must not leave an untracked published duplicate.
            if (!journal.edit().putString(id, Gson().toJson(saved)).commit()) throw IOException("无法记录保存状态")
            resolver.openFileDescriptor(uri, "w")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { out ->
                    var copied = 0L
                    file.inputStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (cancelled()) throw TransferCancelled()
                            val n = input.read(buffer); if (n < 0) break
                            out.write(buffer, 0, n); copied += n; progress(copied)
                        }
                    }
                    if (copied != file.length()) throw IOException("保存的文件长度不一致")
                    out.flush(); out.fd.sync()
                }
            } ?: throw IOException("无法保存文件")
            val actualName = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
                }
            }.getOrNull() ?: name
            saved = saved.copy(name = actualName)
            if (!journal.edit().putString(id, Gson().toJson(saved)).commit()) throw IOException("无法记录保存状态")
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("无法完成系统文件保存")
            return saved
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }.onSuccess { journal.edit().remove(id).commit() }
            if (e is TransferCancelled) throw e
            throw IOException("保存失败：${e.message ?: "存储空间不足"}", e)
        }
    }

    /** Called only after the saved-file ledger has been durably committed. */
    fun acknowledgePublication(context: Context, id: String) {
        context.getSharedPreferences("publication-journal", Context.MODE_PRIVATE).edit().remove(id).commit()
    }

    /** Startup-only recovery. Completed Downloads are retained; only our unfinished pending rows are removed. */
    fun recoverPublications(context: Context, cleanupPending: Boolean = true): List<SavedFile> {
        val journal = context.getSharedPreferences("publication-journal", Context.MODE_PRIVATE)
        val recovered = mutableListOf<SavedFile>()
        val resolver = context.contentResolver
        journal.all.forEach { (id, raw) ->
            val saved = runCatching { Gson().fromJson(raw as String, SavedFile::class.java) }.getOrNull()
            if (saved == null) { journal.edit().remove(id).commit(); return@forEach }
            runCatching {
                val uri = Uri.parse(saved.uri)
                var pending = true
                var exists = false
                var actualName = saved.name
                var size = -1L
                resolver.query(uri, arrayOf(MediaStore.Downloads.IS_PENDING, OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) { exists = true; pending = cursor.getInt(0) != 0; size = cursor.getLong(1); actualName = cursor.getString(2) ?: actualName }
                }
                if (exists && !pending && size == saved.size) recovered.add(saved.copy(name = actualName))
                else {
                    if (exists && pending && !cleanupPending) return@runCatching
                    if (exists && pending) resolver.delete(uri, null, null)
                    journal.edit().remove(id).commit()
                }
            }
        }
        // Covers the tiny crash window between insert() and the first journal commit.
        if (cleanupPending) runCatching {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.Downloads.IS_PENDING}=1 AND ${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
            val arguments = arrayOf("$downloadsPath/", context.packageName)
            val projection = arrayOf(MediaStore.Downloads._ID)
            // Android 10 requires the URI flag; its provider predates the API 30 query argument.
            val pendingRows = if (Build.VERSION.SDK_INT >= 30) {
                val query = android.os.Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arguments)
                }
                resolver.query(collection, projection, query, null)
            } else {
                @Suppress("DEPRECATION")
                val includingPending = MediaStore.setIncludePending(collection)
                resolver.query(includingPending, projection, selection, arguments, null)
            }
            pendingRows?.use { cursor ->
                while (cursor.moveToNext()) resolver.delete(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null)
            }
        }
        return recovered
    }

    fun isReadable(context: Context, file: SavedFile): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(file.uri), "r")?.use { it.statSize == file.size } ?: false
    }.getOrDefault(false)

    fun openDownloads(context: Context) {
        val directory = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:$downloadsPath"
        )
        val openDirectory = Intent(Intent.ACTION_VIEW)
            .setDataAndType(directory, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(openDirectory)
        } catch (_: ActivityNotFoundException) {
            openSystemDownloads(context)
        } catch (_: SecurityException) {
            openSystemDownloads(context)
        }
    }

    private fun openSystemDownloads(context: Context) {
        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun open(context: Context, file: SavedFile) {
        val uri = if (file.uri.startsWith("content:")) Uri.parse(file.uri) else FileProvider.getUriForFile(context, "${context.packageName}.files", File(file.uri))
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(file.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "打开文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

fun bytes(value: Long): String {
    if (value < 0) return "未知大小"
    if (value < 1024) return "$value B"
    var n = value.toDouble(); var unit = 0
    val units = arrayOf("KB", "MB", "GB", "TB")
    do { n /= 1024; unit++ } while (n >= 1024 && unit < 4)
    return String.format(java.util.Locale.ROOT, if (n < 10) "%.2f %s" else "%.1f %s", n, units[unit - 1])
}

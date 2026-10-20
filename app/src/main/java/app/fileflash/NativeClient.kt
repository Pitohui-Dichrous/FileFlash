package app.fileflash

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class RemoteFile(val id: String = "", val name: String = "", val size: Long = 0, val mime: String = "application/octet-stream", val sha256: String? = null, val received: Boolean = false, val createdAt: Long = 0)
data class TextEntry(val id: String = "", val text: String = "", val sender: String = "", val createdAt: Long = 0)
data class ClientEntry(val id: String = "", val name: String = "", val lastSeen: Long = 0)
data class UploadEntry(val id: String = "", val name: String = "", val size: Long = 0, val offset: Long = 0, val status: String = "", val error: String? = null, val createdAt: Long = 0, val speed: Long = 0)
data class DownloadEntry(val id: String = "", val name: String = "", val size: Long = 0, val transferred: Long = 0, val status: String = "", val error: String? = null, val speed: Long = 0)
data class ServerState(val serverName: String = "FileFlash", val files: List<RemoteFile> = emptyList(), val texts: List<TextEntry> = emptyList(), val uploads: List<UploadEntry> = emptyList(), val clients: List<ClientEntry> = emptyList(), val downloads: List<DownloadEntry> = emptyList())
data class NativeTask(val id: String = UUID.randomUUID().toString(), val name: String, val direction: String, val size: Long, val transferred: Long = 0, val speed: Long = 0, val status: String = "等待", val error: String? = null, val source: LocalFile? = null, val remote: RemoteFile? = null, val savedUri: String? = null, val endpoint: String? = null, val updatedAt: Long = System.currentTimeMillis())
data class UploadResume(val id: String, val key: String, val name: String, val size: Long, val uri: String, val endpoint: String, val updatedAt: Long = System.currentTimeMillis())

class TransferCancelled : IOException("已暂停，可重试继续")
class HttpFailure(val statusCode: Int, message: String) : IOException(message)
class NativeClient(private val context: Context, val endpoint: String, private val session: String) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("client-resume", Context.MODE_PRIVATE)
    @Volatile var currentConnection: HttpURLConnection? = null
    val cancelled = AtomicBoolean(false)

    fun cancel() { cancelled.set(true); currentConnection?.disconnect() }
    fun taskClient() = NativeClient(context, endpoint, session)
    private fun checkCancel() { if (cancelled.get()) throw TransferCancelled() }

    fun request(method: String, path: String, json: Any? = null, headers: Map<String, String> = emptyMap(), binary: ByteArray? = null): JsonObject {
        checkCancel()
        val c = connection(method, path, headers)
        try {
            val body = binary ?: json?.let { gson.toJson(it).toByteArray(Charsets.UTF_8) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", if (binary != null) "application/octet-stream" else "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val input = if (code in 200..299) c.inputStream else c.errorStream
            val raw = input?.use { source ->
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream()
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > 8 * 1024 * 1024) throw IOException("对方响应超出限制")
                    output.write(buffer, 0, n)
                }
                output.toString("UTF-8")
            } ?: "{}"
            val parsed = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrDefault(JsonObject())
            if (code !in 200..299) throw HttpFailure(code, parsed.get("error")?.asString ?: "连接失败（$code）")
            return parsed
        } catch (e: Exception) {
            if (cancelled.get()) throw TransferCancelled()
            throw e
        } finally { c.disconnect(); if (currentConnection === c) currentConnection = null }
    }

    private fun connection(method: String, path: String, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val c = URL(endpoint + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 12_000; c.readTimeout = if (path.endsWith("/complete")) 600_000 else 45_000
        c.instanceFollowRedirects = false; c.useCaches = false
        c.setRequestProperty("Authorization", "Bearer $session")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        currentConnection = c
        return c
    }

    fun state(): ServerState = gson.fromJson(request("GET", "/api/state"), ServerState::class.java)
    fun sendText(text: String) { request("POST", "/api/texts", mapOf("text" to text)) }

    fun upload(file: LocalFile, progress: (Long, String) -> Unit) {
        val cacheKey = sha256((endpoint + "|" + file.uri + "|" + file.size).toByteArray())
        var resume = prefs.getString(cacheKey, null)?.let { runCatching { gson.fromJson(it, UploadResume::class.java) }.getOrNull() }
        var upload: JsonObject? = null
        if (resume != null) {
            upload = runCatching { request("GET", "/api/uploads/${resume!!.id}", headers = mapOf("X-Upload-Key" to resume!!.key)) }.getOrNull()
            if (upload?.get("status")?.asString == "complete") {
                val digest = MessageDigest.getInstance("SHA-256")
                var checked = 0L
                Storage.stream(context, file).use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        checkCancel(); val n = input.read(buffer); if (n < 0) break
                        digest.update(buffer, 0, n); checked += n
                        if (checked > file.size) throw IOException("源文件大小已变更")
                        progress(checked, "校验源文件")
                    }
                }
                if (checked != file.size) throw IOException("源文件大小已变更")
                if (upload.get("sha256")?.asString.equals(digest.digest().hex(), true)) {
                    prefs.edit().remove(cacheKey).apply(); progress(file.size, "完成"); return
                }
                upload = null; resume = null
                prefs.edit().remove(cacheKey).apply()
            }
        }
        if (upload == null || upload.get("status")?.asString == "cancelled") {
            upload = request("POST", "/api/uploads", mapOf("name" to file.name, "size" to file.size, "mime" to file.mime))
            resume = UploadResume(upload.get("id").asString, upload.get("resumeKey").asString, file.name, file.size, file.uri, endpoint)
            prefs.edit().putString(cacheKey, gson.toJson(resume)).apply()
        }
        val record = resume ?: throw IOException("缺少续传凭据")
        prefs.edit().putString(cacheKey, gson.toJson(record.copy(updatedAt = System.currentTimeMillis()))).apply()
        var lastResumeTouch = System.currentTimeMillis()
        val headers = mapOf("X-Upload-Key" to record.key)
        var offset = upload.get("offset").asLong
        if (offset < 0 || offset > file.size) throw IOException("对方返回了无效的文件偏移")
        val digest = MessageDigest.getInstance("SHA-256")
        Storage.stream(context, file).use { input ->
            val buf = ByteArray(2 * 1024 * 1024)
            var consumed = 0L
            // Re-read the prefix to verify the whole file, even when resuming at a chunk boundary.
            while (consumed < offset) {
                checkCancel()
                val n = input.read(buf, 0, minOf(buf.size.toLong(), offset - consumed).toInt())
                if (n < 0) throw IOException("源文件已变更，请重新选择")
                digest.update(buf, 0, n); consumed += n
                progress(offset, "校验续传文件")
            }
            while (offset < file.size) {
                checkCancel()
                val wanted = minOf(buf.size.toLong(), file.size - offset).toInt()
                var count = 0
                while (count < wanted) {
                    val n = input.read(buf, count, wanted - count)
                    if (n < 0) throw IOException("源文件大小已变更")
                    count += n
                }
                val chunk = if (count == buf.size) buf else buf.copyOf(count)
                val chunkHeaders = headers + mapOf("X-Offset" to offset.toString(), "X-Chunk-SHA256" to sha256(chunk))
                var succeeded = false
                var failure: Exception? = null
                for (attempt in 0..2) {
                    checkCancel()
                    try {
                        val result = request("PUT", "/api/uploads/${record.id}", headers = chunkHeaders, binary = chunk)
                        val next = result.get("offset").asLong
                        if (next != offset + count) throw IOException("分块确认偏移不一致")
                        succeeded = true; break
                    } catch (e: Exception) {
                        failure = e
                        if (e is TransferCancelled) throw e
                        // A lost response may follow a successful write. Confirm before retransmitting.
                        val remoteOffset = runCatching { request("GET", "/api/uploads/${record.id}", headers = headers).get("offset").asLong }.getOrNull()
                        if (remoteOffset == offset + count) { succeeded = true; break }
                        if (attempt < 2) Thread.sleep(500L * (attempt + 1))
                    }
                }
                if (!succeeded) throw failure ?: IOException("分块发送失败")
                digest.update(chunk); offset += count
                if (System.currentTimeMillis() - lastResumeTouch > 10_000) {
                    lastResumeTouch = System.currentTimeMillis()
                    prefs.edit().putString(cacheKey, gson.toJson(record.copy(updatedAt = lastResumeTouch))).apply()
                }
                progress(offset, "传输中")
            }
            if (input.read() != -1) throw IOException("源文件大小已变更")
        }
        progress(offset, "校验中")
        request("POST", "/api/uploads/${record.id}/complete", mapOf("sha256" to digest.digest().hex()), headers)
        prefs.edit().remove(cacheKey).apply()
        progress(file.size, "完成")
    }

    fun download(file: RemoteFile, taskId: String, progress: (Long, String) -> Unit): SavedFile {
        val dir = File(context.filesDir, "client-parts").apply { mkdirs() }
        val local = File(dir, "$taskId.part")
        var offset = local.length()
        if (offset > file.size) { local.delete(); offset = 0 }
        val ticket = request("POST", "/api/files/${file.id}/ticket", emptyMap<String, String>()).get("url").asString
        if (!ticket.startsWith("/api/downloads/")) throw IOException("无效的下载凭据")
        if (offset < file.size || !local.exists()) {
            val c = connection("GET", ticket, if (offset > 0) mapOf("Range" to "bytes=$offset-") else emptyMap())
            try {
                val code = c.responseCode
                if (code != 200 && code != 206) throw IOException("下载失败（$code）")
                if (offset > 0 && code == 200) { local.delete(); offset = 0 }
                if (code == 206 && c.getHeaderField("Content-Range")?.startsWith("bytes $offset-") != true) throw IOException("下载续传位置不一致")
                RandomAccessFile(local, "rw").use { output ->
                    output.seek(offset)
                    c.inputStream.use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            checkCancel()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (offset + n > file.size) throw IOException("下载大小超出声明值")
                            output.write(buffer, 0, n); offset += n
                            progress(offset, "传输中")
                        }
                    }
                    output.fd.sync()
                }
            } catch (e: Exception) {
                if (cancelled.get()) throw TransferCancelled()
                throw e
            } finally { c.disconnect(); currentConnection = null }
        }
        if (local.length() != file.size) throw IOException("连接已中断，可重试继续下载")
        if (!file.sha256.isNullOrBlank()) {
            progress(offset, "校验中")
            val digest = MessageDigest.getInstance("SHA-256")
            local.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) { checkCancel(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            if (!digest.digest().hex().equals(file.sha256, true)) { local.delete(); throw IOException("文件校验不一致，请重新下载") }
        }
        progress(offset, "保存中")
        // Verified but not yet published data is recoverable user data, never an idle-cache eviction candidate.
        val verified = File(dir, "$taskId.verified")
        verified.writeText(file.sha256 ?: "length:${file.size}")
        val saved = Storage.publish(context, taskId, local, file.name, file.mime, sha256 = file.sha256, cancelled = { cancelled.get() })
        local.delete()
        verified.delete()
        progress(offset, "完成")
        return saved
    }

    companion object {
        fun forgetUploadResume(context: Context, file: LocalFile, endpoint: String): UploadResume? {
            val key = sha256((endpoint + "|" + file.uri + "|" + file.size).toByteArray())
            val prefs = context.getSharedPreferences("client-resume", Context.MODE_PRIVATE)
            val record = prefs.getString(key, null)?.let { runCatching { Gson().fromJson(it, UploadResume::class.java) }.getOrNull() }
            prefs.edit().remove(key).commit()
            return record
        }

        fun parseAddress(raw: String): Pair<String, String> {
            val uri = runCatching { URI(if (raw.trim().contains("://")) raw.trim() else "http://${raw.trim()}") }.getOrElse { throw IOException("连接地址无效") }
            if (uri.scheme != "http" || uri.host.isNullOrBlank() || uri.userInfo != null || (uri.path ?: "").let { it.isNotBlank() && it != "/" }) throw IOException("请输入 FileFlash 的完整局域网连接地址")
            val fragment = uri.rawFragment ?: ""
            val token = fragment.split('&').firstOrNull { it.startsWith("pair=") }?.substringAfter("pair=")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
            val host = if (uri.host.contains(':')) "[${uri.host}]" else uri.host
            return "http://$host${if (uri.port >= 0) ":${uri.port}" else ""}" to token
        }

        fun pair(context: Context, address: String, manualToken: String): Pair<NativeClient, String> {
            val (endpoint, embeddedToken) = parseAddress(address)
            val token = embeddedToken.ifBlank { manualToken.trim() }
            if (token.isBlank()) throw IOException("请粘贴完整二维码链接，或输入配对码")
            val initial = NativeClient(context, endpoint, "")
            val result = initial.request("POST", "/api/pair", mapOf("token" to token, "name" to android.os.Build.MODEL.take(80)))
            return NativeClient(context, endpoint, result.get("session").asString) to endpoint
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

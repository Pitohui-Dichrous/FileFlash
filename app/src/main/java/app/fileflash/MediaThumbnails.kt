package app.fileflash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import app.fileflash.core.FileKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** Small JPEGs only, in memory. Neither originals nor previews are copied to disk. */
internal class MediaThumbnails(private val context: Context) {
    private val epoch = AtomicLong()
    private val decoder = Semaphore(1)
    private val cache = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    fun clear() = synchronized(cache) { epoch.incrementAndGet(); cache.evictAll() }
    fun get(key: String): ByteArray? = cache.get(key)

    fun cached(key: String, load: () -> ByteArray?): ByteArray? {
        cache.get(key)?.let { return it }
        val generation = epoch.get()
        val data = load()?.takeIf { it.size <= MAX_BYTES } ?: return null
        synchronized(cache) { if (generation == epoch.get()) cache.put(key, data) }
        return data
    }

    fun create(uri: Uri, name: String, mime: String): ByteArray? {
        // Local rows and the HTTP provider share one decoder; a short overlap is not a codec failure.
        if (!decoder.tryAcquire(5, TimeUnit.SECONDS)) return null
        var bitmap: Bitmap? = null
        try {
            bitmap = when (FileKind.classify(name, mime)) {
                FileKind.IMAGE -> {
                    val source = if (uri.scheme == "file") ImageDecoder.createSource(File(requireNotNull(uri.path)))
                        else ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { image, info, _ ->
                        image.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = minOf(1.0, EDGE.toDouble() / max(info.size.width, info.size.height))
                        image.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
                    }
                }
                FileKind.VIDEO -> videoFrame(uri)
                else -> null
            } ?: return null
            // Providers may return a larger image than requested; constrain before encoding.
            val scale = minOf(1.0, EDGE.toDouble() / max(bitmap.width, bitmap.height))
            if (scale < 1) {
                val smaller = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true)
                if (smaller !== bitmap) bitmap.recycle()
                bitmap = smaller
            }
            return ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                output.toByteArray().takeIf { it.size <= MAX_BYTES }
            }
        } catch (_: Exception) {
            return null // Unsupported codecs and expired source grants use the file icon.
        } finally { bitmap?.recycle(); decoder.release() }
    }

    private fun videoFrame(uri: Uri): Bitmap? {
        if (uri.scheme == "content") {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(EDGE, EDGE), CancellationSignal()) }.getOrNull()?.let { return it }
        }
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") retriever.setDataSource(requireNotNull(uri.path))
            else retriever.setDataSource(context, uri)
            return retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, EDGE, EDGE)
        } finally { retriever.release() }
    }

    companion object {
        const val EDGE = 256
        const val MAX_BYTES = 256 * 1024
        fun decode(data: ByteArray): Bitmap? {
            if (data.size > MAX_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth !in 1..EDGE || bounds.outHeight !in 1..EDGE) return null
            return BitmapFactory.decodeByteArray(data, 0, data.size)
        }
    }
}

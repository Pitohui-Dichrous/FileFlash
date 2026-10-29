package app.fileflash

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Picture
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fileflash.core.FileKind
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The three bundled vectors remain unchanged; Pictures retain vector drawing commands. */
private object FileArtwork {
    private val pictures = mutableMapOf<String, Picture>()

    @Synchronized fun picture(assets: AssetManager, name: String): Picture =
        pictures.getOrPut(name) { SVG.getFromAsset(assets, "web/file-icons/$name.svg").renderToPicture() }
}

@Composable internal fun FilePreview(file: RemoteFile, repository: TransferRepository, remote: Boolean, modifier: Modifier = Modifier) {
    val epoch by repository.previewEpoch.collectAsStateWithLifecycle()
    val kind = FileKind.classify(file.name, file.mime)
    val isMedia = kind == FileKind.IMAGE || kind == FileKind.VIDEO
    val bitmap by produceState<Bitmap?>(null, file.id, file.size, file.createdAt, file.name, file.mime, remote, epoch) {
        value = null
        if (isMedia) {
            try { value = repository.thumbnail(file, remote) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Missing or unsupported media retains its file artwork. */ }
        }
    }
    val label = when (kind) {
        FileKind.IMAGE -> "图片"
        FileKind.VIDEO -> "视频"
        FileKind.DOCUMENT -> "文档"
        FileKind.AUDIO -> "音频"
        else -> "文件"
    }
    val artwork = when (kind) {
        FileKind.DOCUMENT -> "documents-folder"
        FileKind.AUDIO -> "audio-eighth-note"
        else -> "unknown-file"
    }
    val assets = LocalContext.current.applicationContext.assets
    val picture by produceState<Picture?>(null, artwork) {
        value = withContext(Dispatchers.IO) { FileArtwork.picture(assets, artwork) }
    }
    Box(modifier.size(80.dp).clipToBounds().background(Color.Black).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        val preview = bitmap
        if (preview != null) {
            Image(preview.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            picture?.let { vector ->
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas -> canvas.nativeCanvas.drawPicture(vector, RectF(0f, 0f, size.width, size.height)) }
                }
            }
        }
        if (kind == FileKind.VIDEO) {
            Box(Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = .8f)).padding(4.dp)) {
                AcidIcon(R.drawable.ic_acid_play, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

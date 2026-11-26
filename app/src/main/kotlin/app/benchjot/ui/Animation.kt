package app.benchjot.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.benchjot.core.OutputItem
import app.benchjot.core.outputLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// A simulation's animation (a run that saved GIF and/or video, no still): plays as it is, as on the
// web. GIF moves on Android 9+ (AnimatedImageDrawable); older phones show its first frame. A video
// (MP4, WebM) plays muted on a loop from the app's cache.

private sealed interface Loaded {
    data object Loading : Loaded
    data class Gif(val drawable: android.graphics.drawable.Drawable) : Loaded
    data class Still(val image: ImageBitmap) : Loaded
    data class Video(val file: File) : Loaded
    data object Failed : Loaded
}

@Composable
fun AnimationPreview(item: OutputItem, files: OutputFiles, maxHeight: Int = 480) {
    val context = LocalContext.current
    var loaded by remember(item, files) { mutableStateOf<Loaded>(Loaded.Loading) }
    LaunchedEffect(item, files) {
        loaded = runCatching {
            withContext(Dispatchers.IO) {
                val gif = item.files["gif"]
                val video = item.files["mp4"] ?: item.files["webm"]
                // The video first when there is one (server runs make it beside the GIF): full colour,
                // smoother and far smaller; else the GIF.
                when {
                    video != null -> {
                        val f = File(context.cacheDir, "anim/${item.hashCode()}-${video.substringAfterLast('/')}")
                        if (!f.exists()) { f.parentFile?.mkdirs(); f.writeBytes(files.bytes(video)) }
                        Loaded.Video(f)
                    }
                    gif != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                        val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(files.bytes(gif))))
                        Loaded.Gif(d)
                    }
                    gif != null -> {
                        val bytes = files.bytes(gif)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { Loaded.Still(it.asImageBitmap()) } ?: Loaded.Failed
                    }
                    else -> Loaded.Failed
                }
            }
        }.getOrDefault(Loaded.Failed)
    }
    val label = item.title.ifEmpty { outputLabel(item) }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val h = imagePlaceholderHeight(maxWidth.value, item.width, item.height).coerceAtMost(maxHeight.toFloat()).dp
        when (val l = loaded) {
            Loaded.Loading, Loaded.Failed -> Box(Modifier.fillMaxWidth().height(h))
            is Loaded.Still -> Image(l.image, label, Modifier.fillMaxWidth().heightIn(max = maxHeight.dp), contentScale = ContentScale.Fit)
            is Loaded.Gif -> AndroidView(
                factory = { c -> ImageView(c).apply { contentDescription = label; adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { v ->
                    v.setImageDrawable(l.drawable)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (l.drawable as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
                },
                modifier = Modifier.fillMaxWidth().height(h),
            )
            is Loaded.Video -> AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        contentDescription = label
                        setVideoPath(l.file.path)
                        setOnPreparedListener { p -> p.isLooping = true; p.setVolume(0f, 0f); start() }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(h),
            )
        }
    }
}

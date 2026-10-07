package com.webwallpaper.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.transformer.Effects
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Limits + compression for video wallpapers.
 * Huge / 4K / high-bitrate videos are what crash the wallpaper and fill the cache, so anything above the
 * limits below is re-encoded to H.264, max 1080 x 1920, at a sane bitrate.
 */
object VideoCompressor {
    const val MAX_BYTES = 1L shl 30            // refuse files over 1 GB outright
    const val MAX_DURATION_MS = 3 * 60 * 1000L // refuse videos longer than 3 minutes
    const val COMPRESS_ABOVE_BYTES = 15L shl 20 // anything bigger than 15 MB gets compressed
    const val FALLBACK_RAW_MAX_BYTES = 60L shl 20 // if compression fails, only keep the original up to 60 MB
    private const val MAX_SHORT = 1080
    private const val MAX_LONG = 1920

    /** Size of the video as it will be displayed (rotation already applied). */
    class Info(val w: Int, val h: Int, val durationMs: Long)

    fun probe(ctx: Context, uri: Uri): Info? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            Info(w, h, dur)
        } catch (e: Exception) { null } finally { try { r.release() } catch (e: Exception) { } }
    }

    private fun scaleFor(i: Info): Float {
        val short = min(i.w, i.h).toFloat(); val long = maxOf(i.w, i.h).toFloat()
        return min(1f, min(MAX_SHORT / short, MAX_LONG / long))
    }

    fun needsCompress(i: Info?, size: Long): Boolean =
        size > COMPRESS_ABOVE_BYTES || (i != null && scaleFor(i) < 1f)

    /** Must be called on the main thread. [done] is called on the main thread too. */
    fun compress(ctx: Context, uri: Uri, out: File, info: Info?, onProgress: (Int) -> Unit, done: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        try {
            val f = if (info != null) scaleFor(info) else 1f
            var targetH = ((info?.h ?: 1920) * f).roundToInt()
            if (targetH % 2 == 1) targetH -= 1                       // encoders want even sizes
            val pixels = if (info != null) (info.w * f * info.h * f) else 1920f * 1080f
            val bitrate = if (pixels > 1280f * 720f) 4_000_000 else 2_500_000

            val effects = Effects(emptyList(), listOf<Effect>(Presentation.createForHeight(targetH.coerceAtLeast(2))))
            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()
            val encoder = DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                .build()

            var finished = false
            lateinit var transformer: Transformer
            val progress = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (finished) return
                    if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(progress.progress)
                    main.postDelayed(this, 500)
                }
            }
            transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished = true; done(true)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        finished = true; try { out.delete() } catch (e: Exception) { }; done(false)
                    }
                })
                .build()
            transformer.start(item, out.absolutePath)
            main.postDelayed(poll, 500)
        } catch (e: Throwable) {
            try { out.delete() } catch (x: Exception) { }
            done(false)
        }
    }
}

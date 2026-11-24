package com.vibecoding.reader.ui.bookshelf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.vibecoding.reader.domain.reader.CoverDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 封面解码：后台线程、采样、少量内存缓存。不要在组合阶段同步 decodeFile。
 */
object CoverBitmapCache {
    private const val MAX_ENTRIES = 64
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized
    fun decode(path: String, maxEdge: Int = CoverDecode.DEFAULT_MAX_EDGE): ImageBitmap? {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return null
        val key = "${file.absolutePath}|${file.lastModified()}|$maxEdge"
        cache[key]?.let { return it.asImageBitmap() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = CoverDecode.inSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        cache[key] = bmp
        return bmp.asImageBitmap()
    }
}

@Composable
fun rememberDecodedCover(
    path: String?,
    maxEdge: Int = CoverDecode.DEFAULT_MAX_EDGE
): ImageBitmap? {
    var image by remember(path, maxEdge) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, maxEdge) {
        if (path.isNullOrBlank()) {
            image = null
            return@LaunchedEffect
        }
        image = withContext(Dispatchers.IO) {
            CoverBitmapCache.decode(path, maxEdge)
        }
    }
    return image
}

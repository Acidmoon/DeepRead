package com.vibecoding.reader.ui.reader.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecoding.reader.domain.model.PageTurnMode
import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.ReadingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfReader(
    filePath: String,
    settings: ReadingSettings,
    initialPosition: String,
    jumpPosition: String?,
    onJumpConsumed: () -> Unit,
    onProgress: (position: String, progress: Float, pageIndex: Int, pageCount: Int) -> Unit,
    onToggleChrome: () -> Unit,
    onPageCount: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pageCount by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    val rendererHolder = remember { PdfRendererHolder() }
    val scope = rememberCoroutineScope()

    DisposableEffect(filePath) {
        val file = File(filePath)
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            rendererHolder.open(pfd)
            pageCount = rendererHolder.pageCount
            onPageCount(pageCount)
            ready = true
        } catch (e: Exception) {
            error = e.message ?: "无法打开 PDF"
            ready = false
        }
        onDispose {
            rendererHolder.close()
        }
    }

    val initialPage = (ReaderPosition.parse(initialPosition) as? ReaderPosition.PageIndex)
        ?.page?.coerceAtLeast(0) ?: 0

    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pageCount.coerceAtLeast(1) }
    )

    LaunchedEffect(jumpPosition, pageCount) {
        val jump = jumpPosition ?: return@LaunchedEffect
        val page = (ReaderPosition.parse(jump) as? ReaderPosition.PageIndex)?.page
        if (page != null && pageCount > 0) {
            pagerState.scrollToPage(page.coerceIn(0, pageCount - 1))
        }
        onJumpConsumed()
    }

    LaunchedEffect(pagerState, pageCount) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                if (pageCount <= 0) return@collect
                val progress = (page + 1).toFloat() / pageCount.toFloat()
                onProgress(
                    ReaderPosition.PageIndex(page).serialize(),
                    progress,
                    page,
                    pageCount
                )
            }
    }

    // PDF 暂无重排滚动；上下滚动模式回退为左右滑动 + 点按
    val allowSlide = settings.pageTurnMode != PageTurnMode.TAP
    val allowTap = settings.pageTurnMode == PageTurnMode.TAP ||
        settings.pageTurnMode == PageTurnMode.BOTH ||
        settings.pageTurnMode == PageTurnMode.VERTICAL

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF2A2A2A))
    ) {
        when {
            error != null -> {
                Text(
                    text = error ?: "错误",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            !ready || pageCount == 0 -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            else -> {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = allowSlide,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(page, pageCount) {
                        bitmap = withContext(Dispatchers.IO) {
                            rendererHolder.renderPage(page, context.resources.displayMetrics.densityDpi)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(allowTap, pageCount) {
                                detectTapGestures { offset ->
                                    if (!allowTap) {
                                        onToggleChrome()
                                        return@detectTapGestures
                                    }
                                    val w = size.width.toFloat()
                                    when {
                                        offset.x < w / 3f -> {
                                            scope.launch {
                                                pagerState.animateScrollToPage(
                                                    (pagerState.currentPage - 1).coerceAtLeast(0)
                                                )
                                            }
                                        }
                                        offset.x > w * 2f / 3f -> {
                                            scope.launch {
                                                pagerState.animateScrollToPage(
                                                    (pagerState.currentPage + 1)
                                                        .coerceAtMost(pageCount - 1)
                                                )
                                            }
                                        }
                                        else -> onToggleChrome()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = bitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "PDF 第 ${page + 1} 页",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(8.dp)
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                        Text(
                            text = "${page + 1} / $pageCount",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

private class PdfRendererHolder {
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val mutex = Mutex()

    val pageCount: Int get() = renderer?.pageCount ?: 0

    fun open(descriptor: ParcelFileDescriptor) {
        close()
        pfd = descriptor
        renderer = PdfRenderer(descriptor)
    }

    fun close() {
        renderer?.close()
        pfd?.close()
        renderer = null
        pfd = null
    }

    suspend fun renderPage(index: Int, densityDpi: Int): Bitmap? = mutex.withLock {
        val r = renderer ?: return null
        if (index !in 0 until r.pageCount) return null
        r.openPage(index).use { page ->
            val scale = (densityDpi / 72f).coerceIn(1.5f, 3f)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }
}

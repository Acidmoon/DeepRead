package com.vibecoding.reader.ui.reader.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vibecoding.reader.domain.model.PageTurnMode
import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.ReadingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

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
    val rendererHolder = remember { PdfRendererHolder() }
    var pageCount by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }

    DisposableEffect(filePath) {
        onDispose { rendererHolder.close() }
    }

    LaunchedEffect(filePath) {
        ready = false
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val pfd = ParcelFileDescriptor.open(
                    File(filePath),
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
                rendererHolder.open(pfd)
                rendererHolder.pageCount
            }
        }
        result.onSuccess { count ->
            pageCount = count
            onPageCount(count)
            ready = true
        }.onFailure { e ->
            error = e.message ?: "无法打开 PDF"
            ready = false
        }
    }

    val bg = Color(settings.pdfBackgroundColor)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
    ) {
        when {
            error != null -> {
                Text(
                    text = error ?: "错误",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            !ready || pageCount <= 0 -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            settings.pdfPageTurnMode == PageTurnMode.VERTICAL -> {
                PdfVerticalReader(
                    rendererHolder = rendererHolder,
                    pageCount = pageCount,
                    background = bg,
                    initialPosition = initialPosition,
                    jumpPosition = jumpPosition,
                    onJumpConsumed = onJumpConsumed,
                    onProgress = onProgress,
                    onToggleChrome = onToggleChrome,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                PdfHorizontalReader(
                    rendererHolder = rendererHolder,
                    pageCount = pageCount,
                    settings = settings,
                    background = bg,
                    initialPosition = initialPosition,
                    jumpPosition = jumpPosition,
                    onJumpConsumed = onJumpConsumed,
                    onProgress = onProgress,
                    onToggleChrome = onToggleChrome,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfHorizontalReader(
    rendererHolder: PdfRendererHolder,
    pageCount: Int,
    settings: ReadingSettings,
    background: Color,
    initialPosition: String,
    jumpPosition: String?,
    onJumpConsumed: () -> Unit,
    onProgress: (String, Float, Int, Int) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val initialPage = (ReaderPosition.parse(initialPosition) as? ReaderPosition.PageIndex)
        ?.page?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: 0
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pageCount }
    )

    val allowSlide = settings.pdfPageTurnMode == PageTurnMode.SLIDE ||
        settings.pdfPageTurnMode == PageTurnMode.BOTH
    val allowTap = settings.pdfPageTurnMode == PageTurnMode.TAP ||
        settings.pdfPageTurnMode == PageTurnMode.BOTH

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
                val progress = (page + 1).toFloat() / pageCount.toFloat()
                onProgress(
                    ReaderPosition.PageIndex(page).serialize(),
                    progress,
                    page,
                    pageCount
                )
            }
    }

    HorizontalPager(
        state = pagerState,
        userScrollEnabled = allowSlide,
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(allowTap, pageCount) {
                detectTapGestures { offset ->
                    if (!allowTap) {
                        onToggleChrome()
                        return@detectTapGestures
                    }
                    val w = size.width.toFloat()
                    when {
                        offset.x < w / 3f -> scope.launch {
                            pagerState.animateScrollToPage(
                                (pagerState.currentPage - 1).coerceAtLeast(0)
                            )
                        }
                        offset.x > w * 2f / 3f -> scope.launch {
                            pagerState.animateScrollToPage(
                                (pagerState.currentPage + 1).coerceAtMost(pageCount - 1)
                            )
                        }
                        else -> onToggleChrome()
                    }
                }
            }
    ) { page ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(background),
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            val targetW = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
            val targetH = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
            PdfPageImage(
                rendererHolder = rendererHolder,
                pageIndex = page,
                fitSize = IntSize(targetW, targetH),
                fillWidth = false,
                contentDescription = "PDF 第 ${page + 1} 页",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun PdfVerticalReader(
    rendererHolder: PdfRendererHolder,
    pageCount: Int,
    background: Color,
    initialPosition: String,
    jumpPosition: String?,
    onJumpConsumed: () -> Unit,
    onProgress: (String, Float, Int, Int) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex =
            (ReaderPosition.parse(initialPosition) as? ReaderPosition.PageIndex)
                ?.page?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: 0
    )
    val pages = remember(pageCount) { (0 until pageCount).toList() }

    LaunchedEffect(jumpPosition, pageCount) {
        val jump = jumpPosition ?: return@LaunchedEffect
        val page = (ReaderPosition.parse(jump) as? ReaderPosition.PageIndex)?.page
        if (page != null && pageCount > 0) {
            listState.scrollToItem(page.coerceIn(0, pageCount - 1))
        }
        onJumpConsumed()
    }

    LaunchedEffect(listState, pageCount) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .sample(300)
            .collect { page ->
                val p = page.coerceIn(0, pageCount - 1)
                val progress = (p + 1).toFloat() / pageCount.toFloat()
                onProgress(
                    ReaderPosition.PageIndex(p).serialize(),
                    progress,
                    p,
                    pageCount
                )
            }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onToggleChrome() })
            }
    ) {
        val density = LocalDensity.current
        val viewportW = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(pages, key = { it }) { page ->
                val aspect = rendererHolder.aspectOf(page)
                val pageHeightDp = with(density) {
                    (viewportW / aspect).roundToInt().toDp()
                }
                PdfPageImage(
                    rendererHolder = rendererHolder,
                    pageIndex = page,
                    fitSize = IntSize(
                        viewportW,
                        (viewportW / aspect).roundToInt().coerceAtLeast(1)
                    ),
                    fillWidth = true,
                    contentDescription = "PDF 第 ${page + 1} 页",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(pageHeightDp)
                )
            }
        }
    }
}

@Composable
private fun PdfPageImage(
    rendererHolder: PdfRendererHolder,
    pageIndex: Int,
    fitSize: IntSize,
    fillWidth: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(pageIndex, fitSize.width, fitSize.height, fillWidth) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(pageIndex, fitSize.width, fitSize.height, fillWidth) {
        if (fitSize.width <= 0 || fitSize.height <= 0) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            if (fillWidth) {
                rendererHolder.renderPageFillWidth(pageIndex, fitSize.width)
            } else {
                rendererHolder.renderPageFit(pageIndex, fitSize.width, fitSize.height)
            }
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = if (fillWidth) ContentScale.FillWidth else ContentScale.Fit,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

/**
 * 线程安全 PdfRenderer；按视口像素渲染，保证全屏清晰。
 * 打开时缓存各页宽高比，避免滚动列表反复 openPage。
 */
private class PdfRendererHolder {
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var aspects: FloatArray = floatArrayOf()
    private val mutex = Mutex()

    val pageCount: Int get() = renderer?.pageCount ?: 0

    fun open(descriptor: ParcelFileDescriptor) {
        close()
        pfd = descriptor
        val r = PdfRenderer(descriptor)
        renderer = r
        aspects = FloatArray(r.pageCount) { i ->
            r.openPage(i).use { page ->
                if (page.height <= 0) 1f
                else page.width.toFloat() / page.height.toFloat()
            }
        }
    }

    fun close() {
        runCatching { renderer?.close() }
        runCatching { pfd?.close() }
        renderer = null
        pfd = null
        aspects = floatArrayOf()
    }

    fun aspectOf(index: Int): Float {
        if (index in aspects.indices) return aspects[index]
        return 210f / 297f
    }

    /** 等比缩放完整落入视口（左右翻页，一页铺满屏幕可用区域）。 */
    suspend fun renderPageFit(index: Int, targetW: Int, targetH: Int): Bitmap? = mutex.withLock {
        val r = renderer ?: return null
        if (index !in 0 until r.pageCount) return null
        r.openPage(index).use { page ->
            val scale = min(
                targetW.toFloat() / page.width.toFloat(),
                targetH.toFloat() / page.height.toFloat()
            ).coerceAtLeast(0.1f)
            // 按屏幕物理像素渲染；略抬清晰度
            val w = (page.width * scale).roundToInt().coerceIn(1, MAX_BITMAP_EDGE)
            val h = (page.height * scale).roundToInt().coerceIn(1, MAX_BITMAP_EDGE)
            createAndRender(page, w, h)
        }
    }

    /** 宽度铺满视口（上下滚动）。 */
    suspend fun renderPageFillWidth(index: Int, targetW: Int): Bitmap? = mutex.withLock {
        val r = renderer ?: return null
        if (index !in 0 until r.pageCount) return null
        r.openPage(index).use { page ->
            val scale = (targetW.toFloat() / page.width.toFloat()).coerceAtLeast(0.1f)
            val w = (page.width * scale).roundToInt().coerceIn(1, MAX_BITMAP_EDGE)
            val h = (page.height * scale).roundToInt().coerceIn(1, MAX_BITMAP_EDGE)
            createAndRender(page, w, h)
        }
    }

    private fun createAndRender(page: PdfRenderer.Page, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    companion object {
        private const val MAX_BITMAP_EDGE = 4096
    }
}

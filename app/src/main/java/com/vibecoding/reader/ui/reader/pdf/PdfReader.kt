package com.vibecoding.reader.ui.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.vibecoding.reader.data.parser.PdfOutlineParser
import com.vibecoding.reader.domain.model.PageTurnMode
import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.ReadingSettings
import com.vibecoding.reader.domain.reader.AutoPageTurn
import com.vibecoding.reader.domain.reader.ReadingGestures
import com.vibecoding.reader.ui.common.ReadingLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
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
    onDoubleTap: () -> Unit = {},
    onPageCount: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    bottomSafeInset: Dp = ReadingLayout.statusOverlayContentHeight,
    pauseAutoTurn: Boolean = false,
    /** 本地记住的打开密码；非空时加密文档先尝试免输入解锁 */
    storedPassword: String? = null,
    /** 用户输入密码解锁成功后回调（用于持久化） */
    onPasswordVerified: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf<PdfEngine?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    // 加密 PDF：等待用户输入密码；passwordRejected 表示刚输入的密码不对
    var needsPassword by remember { mutableStateOf(false) }
    var passwordRejected by remember { mutableStateOf(false) }

    DisposableEffect(filePath) {
        onDispose { engine?.close() }
    }

    fun onEngineOpened(opened: PdfEngine) {
        engine = opened
        pageCount = opened.pageCount
        onPageCount(opened.pageCount)
        ready = true
        needsPassword = false
    }

    LaunchedEffect(filePath) {
        ready = false
        error = null
        needsPassword = false
        passwordRejected = false
        var result = withContext(Dispatchers.IO) { openPdfEngine(context, filePath, null) }
        // 加密且有记住的密码：先静默尝试免输入解锁
        if (result == PdfOpenResult.NeedPassword && !storedPassword.isNullOrEmpty()) {
            result = withContext(Dispatchers.IO) {
                openPdfEngine(context, filePath, storedPassword)
            }
        }
        when (result) {
            is PdfOpenResult.Success -> onEngineOpened(result.engine)
            PdfOpenResult.NeedPassword -> needsPassword = true
            is PdfOpenResult.Failure -> {
                error = result.message
                ready = false
            }
        }
    }

    if (needsPassword && engine == null && error == null) {
        PdfPasswordDialog(
            showError = passwordRejected,
            onConfirm = { password ->
                scope.launch {
                    passwordRejected = false
                    val result = withContext(Dispatchers.IO) {
                        openPdfEngine(context, filePath, password)
                    }
                    when (result) {
                        is PdfOpenResult.Success -> {
                            onEngineOpened(result.engine)
                            onPasswordVerified(password)
                        }
                        PdfOpenResult.NeedPassword -> passwordRejected = true
                        is PdfOpenResult.Failure -> {
                            error = result.message
                            needsPassword = false
                        }
                    }
                }
            },
            onCancel = {
                needsPassword = false
                error = "该 PDF 受密码保护，未输入密码"
            }
        )
    }

    val bg = Color(settings.pdfBackgroundColor)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
    ) {
        val pdfEngine = engine
        when {
            error != null -> {
                Text(
                    text = error ?: "错误",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            !ready || pageCount <= 0 || pdfEngine == null -> {
                if (!needsPassword) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
            settings.pdfPageTurnMode == PageTurnMode.VERTICAL -> {
                PdfVerticalReader(
                    rendererHolder = pdfEngine,
                    pageCount = pageCount,
                    settings = settings,
                    background = bg,
                    initialPosition = initialPosition,
                    jumpPosition = jumpPosition,
                    onJumpConsumed = onJumpConsumed,
                    onProgress = onProgress,
                    onToggleChrome = onToggleChrome,
                    onDoubleTap = onDoubleTap,
                    modifier = Modifier.fillMaxSize(),
                    bottomSafeInset = bottomSafeInset,
                    pauseAutoTurn = pauseAutoTurn
                )
            }
            else -> {
                PdfHorizontalReader(
                    rendererHolder = pdfEngine,
                    pageCount = pageCount,
                    settings = settings,
                    background = bg,
                    initialPosition = initialPosition,
                    jumpPosition = jumpPosition,
                    onJumpConsumed = onJumpConsumed,
                    onProgress = onProgress,
                    onToggleChrome = onToggleChrome,
                    onDoubleTap = onDoubleTap,
                    modifier = Modifier.fillMaxSize(),
                    bottomSafeInset = bottomSafeInset,
                    pauseAutoTurn = pauseAutoTurn
                )
            }
        }
    }
}

/** 加密 PDF 的密码输入对话框 */
@Composable
private fun PdfPasswordDialog(
    showError: Boolean,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("需要密码") },
        text = {
            Column {
                Text("该 PDF 已加密，请输入打开密码")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    isError = showError,
                    label = { Text("密码") },
                    supportingText = if (showError) {
                        { Text("密码不正确，请重试") }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty()
            ) {
                Text("打开")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        }
    )
}

private sealed interface PdfOpenResult {
    class Success(val engine: PdfEngine) : PdfOpenResult
    data object NeedPassword : PdfOpenResult
    class Failure(val message: String) : PdfOpenResult
}

/**
 * 打开 PDF：
 * - 未给密码时优先用系统 PdfRenderer（硬件路径，快）
 * - 打不开（多为加密）则交给 PdfBox；密码错误抛 [InvalidPasswordException]
 */
private fun openPdfEngine(context: Context, filePath: String, password: String?): PdfOpenResult {
    val file = File(filePath)
    if (password == null) {
        runCatching {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return PdfOpenResult.Success(AndroidPdfEngine(pfd))
        }
    }
    PdfOutlineParser.ensureInitialized(context)
    return try {
        val doc = if (password == null) {
            PDDocument.load(file)
        } else {
            PDDocument.load(file, password)
        }
        PdfOpenResult.Success(PdfBoxEngine(doc))
    } catch (e: InvalidPasswordException) {
        PdfOpenResult.NeedPassword
    } catch (e: Exception) {
        PdfOpenResult.Failure(e.message ?: "无法打开 PDF")
    }
}

/** 统一渲染引擎抽象：系统 PdfRenderer 与 PdfBox 回退共用 */
private interface PdfEngine {
    val pageCount: Int
    fun aspectOf(index: Int): Float
    suspend fun renderPageFit(index: Int, targetW: Int, targetH: Int): Bitmap?
    suspend fun renderPageFillWidth(index: Int, targetW: Int): Bitmap?
    fun close()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfHorizontalReader(
    rendererHolder: PdfEngine,
    pageCount: Int,
    settings: ReadingSettings,
    background: Color,
    initialPosition: String,
    jumpPosition: String?,
    onJumpConsumed: () -> Unit,
    onProgress: (String, Float, Int, Int) -> Unit,
    onToggleChrome: () -> Unit,
    onDoubleTap: () -> Unit = {},
    modifier: Modifier = Modifier,
    bottomSafeInset: Dp = ReadingLayout.statusOverlayContentHeight,
    pauseAutoTurn: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val initialPage = (ReaderPosition.parse(initialPosition) as? ReaderPosition.PageIndex)
        ?.page?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: 0
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pageCount }
    )

    val allowSlide = ReadingGestures.allowsSlidePageTurn(settings.pdfPageTurnMode)
    val autoActive = settings.autoPageTurnEnabled && !pauseAutoTurn && pageCount > 0
    val zoomState = remember { PdfZoomState() }

    // 翻页后回到 1 倍，避免下一页沿用上一页的放大
    LaunchedEffect(pagerState.currentPage) {
        zoomState.reset()
    }

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

    fun goNext(): Boolean {
        if (pagerState.currentPage >= pageCount - 1) return false
        scope.launch {
            pagerState.animateScrollToPage(
                (pagerState.currentPage + 1).coerceAtMost(pageCount - 1)
            )
        }
        return true
    }

    LaunchedEffect(autoActive, settings.autoPageIntervalSec, pagerState.currentPage, pageCount) {
        if (!autoActive) return@LaunchedEffect
        while (isActive) {
            delay(AutoPageTurn.intervalMs(settings.autoPageIntervalSec))
            if (!goNext()) break
        }
    }

    HorizontalPager(
        state = pagerState,
        // 放大后禁用翻页滑动（缩放手势已消费拖动，这里是双保险）
        userScrollEnabled = allowSlide && !zoomState.isZoomed,
        // 日漫从右往左：页序反转，右滑进入下一页；页码与进度仍按真实页索引上报
        reverseLayout = settings.pdfRightToLeft,
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(pageCount) {
                detectTapGestures(
                    onDoubleTap = { onDoubleTap() },
                    onTap = { offset ->
                        if (
                            ReadingGestures.resolveTap(offset.x, size.width.toFloat()) ==
                            ReadingGestures.TapAction.TOGGLE_CHROME
                        ) {
                            onToggleChrome()
                        }
                    }
                )
            }
    ) { page ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .padding(bottom = bottomSafeInset)
                .pdfZoomable(zoomState),
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
    rendererHolder: PdfEngine,
    pageCount: Int,
    settings: ReadingSettings,
    background: Color,
    initialPosition: String,
    jumpPosition: String?,
    onJumpConsumed: () -> Unit,
    onProgress: (String, Float, Int, Int) -> Unit,
    onToggleChrome: () -> Unit,
    onDoubleTap: () -> Unit = {},
    modifier: Modifier = Modifier,
    bottomSafeInset: Dp = ReadingLayout.statusOverlayContentHeight,
    pauseAutoTurn: Boolean = false
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex =
            (ReaderPosition.parse(initialPosition) as? ReaderPosition.PageIndex)
                ?.page?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: 0
    )
    val pages = remember(pageCount) { (0 until pageCount).toList() }
    val zoomState = remember { PdfZoomState() }

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

    val autoActive = settings.autoPageTurnEnabled && !pauseAutoTurn
    // PDF 竖滑：用电子书字号/行距估行高（与设置一致），否则用固定 24sp 行高
    LaunchedEffect(autoActive, settings.autoScrollLinesPerSec, settings.fontSizeSp) {
        if (!autoActive) return@LaunchedEffect
        val frameMs = 16L
        while (isActive) {
            val px = AutoPageTurn.scrollPxPerFrame(
                fontSizeSp = settings.fontSizeSp.coerceAtLeast(16f),
                lineSpacing = settings.lineSpacingMultiplier,
                density = density.density,
                linesPerSec = settings.autoScrollLinesPerSec,
                frameMs = frameMs
            )
            listState.scroll { scrollBy(px) }
            delay(frameMs)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onDoubleTap() },
                    onTap = { offset ->
                        if (
                            ReadingGestures.resolveTap(offset.x, size.width.toFloat()) ==
                            ReadingGestures.TapAction.TOGGLE_CHROME
                        ) {
                            onToggleChrome()
                        }
                    }
                )
            }
    ) {
        val viewportW = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottomSafeInset + 16.dp),
            // 放大后禁用滚动，拖动用于平移；缩回 1 倍恢复滚动
            userScrollEnabled = !zoomState.isZoomed,
            modifier = Modifier
                .fillMaxSize()
                .pdfZoomable(zoomState)
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
    rendererHolder: PdfEngine,
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

private const val MAX_BITMAP_EDGE = 4096

/**
 * 系统 PdfRenderer 引擎；按视口像素渲染，保证全屏清晰。
 * 构造时缓存各页宽高比，避免滚动列表反复 openPage。
 */
private class AndroidPdfEngine(descriptor: ParcelFileDescriptor) : PdfEngine {
    private var pfd: ParcelFileDescriptor? = descriptor
    private var renderer: PdfRenderer? = null
    private var aspects: FloatArray = floatArrayOf()
    private val mutex = Mutex()

    init {
        val r = PdfRenderer(descriptor)
        renderer = r
        aspects = FloatArray(r.pageCount) { i ->
            r.openPage(i).use { page ->
                if (page.height <= 0) 1f
                else page.width.toFloat() / page.height.toFloat()
            }
        }
    }

    override val pageCount: Int get() = renderer?.pageCount ?: 0

    override fun close() {
        runCatching { renderer?.close() }
        runCatching { pfd?.close() }
        renderer = null
        pfd = null
        aspects = floatArrayOf()
    }

    override fun aspectOf(index: Int): Float {
        if (index in aspects.indices) return aspects[index]
        return 210f / 297f
    }

    /** 等比缩放完整落入视口（左右翻页，一页铺满屏幕可用区域）。 */
    override suspend fun renderPageFit(index: Int, targetW: Int, targetH: Int): Bitmap? =
        mutex.withLock {
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
    override suspend fun renderPageFillWidth(index: Int, targetW: Int): Bitmap? =
        mutex.withLock {
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
}

/**
 * PdfBox 软件渲染引擎：系统 PdfRenderer 打不开的加密 PDF 用它回退。
 * 速度较慢，仅在密码解锁后使用。
 */
private class PdfBoxEngine(private val doc: PDDocument) : PdfEngine {
    private val renderer = PDFRenderer(doc)
    private val mutex = Mutex()
    private val aspects: FloatArray = FloatArray(doc.numberOfPages) { i ->
        runCatching {
            val box = doc.getPage(i).cropBox
            if (box == null || box.height <= 0) 1f
            else box.width / box.height
        }.getOrDefault(1f)
    }

    override val pageCount: Int get() = doc.numberOfPages

    override fun close() {
        runCatching { doc.close() }
    }

    override fun aspectOf(index: Int): Float {
        if (index in aspects.indices) return aspects[index]
        return 210f / 297f
    }

    override suspend fun renderPageFit(index: Int, targetW: Int, targetH: Int): Bitmap? =
        mutex.withLock {
            if (index !in 0 until doc.numberOfPages) return null
            val box = doc.getPage(index).cropBox ?: return null
            val scale = min(
                targetW.toFloat() / box.width,
                targetH.toFloat() / box.height
            ).coerceAtLeast(0.1f)
            renderClamped(index, scale, box)
        }

    override suspend fun renderPageFillWidth(index: Int, targetW: Int): Bitmap? =
        mutex.withLock {
            if (index !in 0 until doc.numberOfPages) return null
            val box = doc.getPage(index).cropBox ?: return null
            val scale = (targetW.toFloat() / box.width).coerceAtLeast(0.1f)
            renderClamped(index, scale, box)
        }

    /** 渲染边长与系统引擎对齐钳制，避免超大位图 OOM */
    private fun renderClamped(index: Int, scale: Float, box: PDRectangle): Bitmap? {
        val clamped = min(
            scale,
            min(MAX_BITMAP_EDGE / box.width, MAX_BITMAP_EDGE / box.height)
        )
        return runCatching {
            renderer.renderImage(index, clamped, ImageType.RGB)
        }.getOrNull()
    }
}

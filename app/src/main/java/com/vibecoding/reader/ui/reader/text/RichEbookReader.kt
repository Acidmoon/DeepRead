package com.vibecoding.reader.ui.reader.text

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecoding.reader.domain.model.EbookBlock
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
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 含图片的电子书阅读：
 * - 上下滚动：LazyColumn 展示全部块（含图）
 * - 左右翻页：文本按页切分，图片单独成页
 */
@Composable
fun RichEbookReader(
    blocks: List<EbookBlock>,
    plainText: String,
    settings: ReadingSettings,
    initialPosition: String,
    jumpPosition: String?,
    onJumpConsumed: () -> Unit,
    onProgress: (position: String, progress: Float) -> Unit,
    onToggleChrome: () -> Unit,
    onDoubleTap: () -> Unit = {},
    modifier: Modifier = Modifier,
    bottomSafeInset: Dp = ReadingLayout.statusOverlayContentHeight,
    pauseAutoTurn: Boolean = false
) {
    if (settings.pageTurnMode == PageTurnMode.VERTICAL) {
        RichVertical(
            blocks = blocks,
            plainText = plainText,
            settings = settings,
            initialPosition = initialPosition,
            jumpPosition = jumpPosition,
            onJumpConsumed = onJumpConsumed,
            onProgress = onProgress,
            onToggleChrome = onToggleChrome,
            onDoubleTap = onDoubleTap,
            modifier = modifier,
            bottomSafeInset = bottomSafeInset,
            pauseAutoTurn = pauseAutoTurn
        )
    } else {
        RichHorizontal(
            blocks = blocks,
            plainText = plainText,
            settings = settings,
            initialPosition = initialPosition,
            jumpPosition = jumpPosition,
            onJumpConsumed = onJumpConsumed,
            onProgress = onProgress,
            onToggleChrome = onToggleChrome,
            onDoubleTap = onDoubleTap,
            modifier = modifier,
            bottomSafeInset = bottomSafeInset,
            pauseAutoTurn = pauseAutoTurn
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun RichVertical(
    blocks: List<EbookBlock>,
    plainText: String,
    settings: ReadingSettings,
    initialPosition: String,
    jumpPosition: String?,
    onJumpConsumed: () -> Unit,
    onProgress: (position: String, progress: Float) -> Unit,
    onToggleChrome: () -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier,
    bottomSafeInset: Dp,
    pauseAutoTurn: Boolean
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val textColor = Color(settings.textColor)
    val bg = Color(settings.backgroundColor)

    fun indexForOffset(offset: Int): Int {
        if (blocks.isEmpty()) return 0
        val idx = blocks.indexOfLast { it.charOffset <= offset }
        return max(0, idx)
    }

    LaunchedEffect(blocks, initialPosition) {
        val offset = (ReaderPosition.parse(initialPosition) as? ReaderPosition.CharOffset)?.offset
            ?: return@LaunchedEffect
        listState.scrollToItem(indexForOffset(offset))
    }

    LaunchedEffect(jumpPosition, blocks) {
        val jump = jumpPosition ?: return@LaunchedEffect
        val offset = (ReaderPosition.parse(jump) as? ReaderPosition.CharOffset)?.offset
        if (offset != null) {
            listState.scrollToItem(indexForOffset(offset))
        }
        onJumpConsumed()
    }

    LaunchedEffect(listState, blocks, plainText.length) {
        snapshotFlow {
            val i = listState.firstVisibleItemIndex.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
            blocks.getOrNull(i)?.charOffset ?: 0
        }
            .distinctUntilChanged()
            .sample(350)
            .collect { offset ->
                onProgress(
                    ReaderPosition.CharOffset(offset).serialize(),
                    TextPaginator.progressForOffset(offset, plainText.length)
                )
            }
    }

    val autoActive = settings.autoPageTurnEnabled && !pauseAutoTurn
    LaunchedEffect(
        autoActive,
        settings.autoScrollLinesPerSec,
        settings.fontSizeSp,
        settings.lineSpacingMultiplier
    ) {
        if (!autoActive) return@LaunchedEffect
        val frameMs = 16L
        while (isActive) {
            val px = AutoPageTurn.scrollPxPerFrame(
                fontSizeSp = settings.fontSizeSp,
                lineSpacing = settings.lineSpacingMultiplier,
                density = density.density,
                linesPerSec = settings.autoScrollLinesPerSec,
                frameMs = frameMs
            )
            listState.scroll { scrollBy(px) }
            delay(frameMs)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = settings.horizontalPaddingDp.dp,
            end = settings.horizontalPaddingDp.dp,
            top = settings.verticalPaddingDp.dp,
            bottom = bottomSafeInset + 24.dp
        ),
        modifier = modifier
            .fillMaxSize()
            .background(bg)
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
        itemsIndexed(blocks, key = { i, b -> "${b.charOffset}-$i-${b::class.simpleName}" }) { _, block ->
            EbookBlockItem(block = block, settings = settings, textColor = textColor)
        }
    }
}

private sealed class RichPage {
    data class Text(val content: String, val startOffset: Int) : RichPage()
    data class Image(val path: String, val alt: String, val offset: Int) : RichPage()
}

@Composable
private fun RichHorizontal(
    blocks: List<EbookBlock>,
    plainText: String,
    settings: ReadingSettings,
    initialPosition: String,
    jumpPosition: String?,
    onJumpConsumed: () -> Unit,
    onProgress: (position: String, progress: Float) -> Unit,
    onToggleChrome: () -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier,
    bottomSafeInset: Dp,
    pauseAutoTurn: Boolean
) {
    val density = LocalDensity.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var pages by remember { mutableStateOf<List<RichPage>>(emptyList()) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    val hPad = with(density) { settings.horizontalPaddingDp.dp.roundToPx() }
    val vPad = with(density) { settings.verticalPaddingDp.dp.roundToPx() }
    val footer = with(density) { ReadingLayout.pageFooterHeight.roundToPx() }
    val bottomSafePx = with(density) { bottomSafeInset.roundToPx() }
    val fontPx = with(density) { settings.fontSizeSp.sp.toPx() }
    val paint = remember(fontPx) { TextPaginator.createPaint(fontPx) }

    LaunchedEffect(
        blocks,
        viewport,
        settings.fontSizeSp,
        settings.lineSpacingMultiplier,
        settings.horizontalPaddingDp,
        settings.verticalPaddingDp,
        bottomSafeInset
    ) {
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val w = (viewport.width - hPad * 2).coerceAtLeast(1)
        val h = (viewport.height - vPad * 2 - footer - bottomSafePx).coerceAtLeast(1)
        pages = withContext(Dispatchers.Default) {
            buildRichPages(blocks, w, h, paint, settings.lineSpacingMultiplier)
        }
    }

    val initialOffset =
        (ReaderPosition.parse(initialPosition) as? ReaderPosition.CharOffset)?.offset ?: 0
    val initialPage = pages.indexOfLast {
        when (it) {
            is RichPage.Text -> it.startOffset <= initialOffset
            is RichPage.Image -> it.offset <= initialOffset
        }
    }.coerceAtLeast(0)

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { pages.size.coerceAtLeast(1) }
    )

    LaunchedEffect(pages, initialPosition) {
        if (pages.isEmpty()) return@LaunchedEffect
        val off = (ReaderPosition.parse(initialPosition) as? ReaderPosition.CharOffset)?.offset ?: 0
        val idx = pages.indexOfLast {
            when (it) {
                is RichPage.Text -> it.startOffset <= off
                is RichPage.Image -> it.offset <= off
            }
        }.coerceAtLeast(0)
        pagerState.scrollToPage(idx)
    }

    LaunchedEffect(jumpPosition, pages) {
        val jump = jumpPosition ?: return@LaunchedEffect
        val off = (ReaderPosition.parse(jump) as? ReaderPosition.CharOffset)?.offset
        if (off != null && pages.isNotEmpty()) {
            val idx = pages.indexOfLast {
                when (it) {
                    is RichPage.Text -> it.startOffset <= off
                    is RichPage.Image -> it.offset <= off
                }
            }.coerceAtLeast(0)
            pagerState.scrollToPage(idx)
        }
        onJumpConsumed()
    }

    LaunchedEffect(pagerState, pages, plainText.length) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                val p = pages.getOrNull(page) ?: return@collect
                val offset = when (p) {
                    is RichPage.Text -> p.startOffset
                    is RichPage.Image -> p.offset
                }
                onProgress(
                    ReaderPosition.CharOffset(offset).serialize(),
                    TextPaginator.progressForOffset(offset, plainText.length)
                )
            }
    }

    val allowSlide = ReadingGestures.allowsSlidePageTurn(settings.pageTurnMode)
    val textColor = Color(settings.textColor)
    val bg = Color(settings.backgroundColor)
    val autoActive = settings.autoPageTurnEnabled && !pauseAutoTurn && pages.isNotEmpty()

    fun goPrev() {
        scope.launch {
            pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
        }
    }

    fun goNext(): Boolean {
        val last = (pages.size - 1).coerceAtLeast(0)
        if (pagerState.currentPage >= last) return false
        scope.launch {
            pagerState.animateScrollToPage(
                (pagerState.currentPage + 1).coerceAtMost(last)
            )
        }
        return true
    }

    LaunchedEffect(autoActive, settings.autoPageIntervalSec, pagerState.currentPage, pages.size) {
        if (!autoActive) return@LaunchedEffect
        while (isActive) {
            delay(AutoPageTurn.intervalMs(settings.autoPageIntervalSec))
            if (!goNext()) break
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .onSizeChanged { viewport = it }
            .pointerInput(pages.size) {
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
            .then(
                if (allowSlide) {
                    Modifier.pointerInput(pages.size) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragAccum = 0f },
                            onDragEnd = {
                                when (ReadingGestures.resolveHorizontalDrag(dragAccum)) {
                                    ReadingGestures.SlideTurn.PREV -> goPrev()
                                    ReadingGestures.SlideTurn.NEXT -> goNext()
                                    null -> Unit
                                }
                                dragAccum = 0f
                            },
                            onDragCancel = { dragAccum = 0f },
                            onHorizontalDrag = { _, dx -> dragAccum += dx }
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        if (pages.isEmpty()) {
            androidx.compose.material3.CircularProgressIndicator(
                Modifier.align(Alignment.Center)
            )
        } else {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = allowSlide,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = settings.horizontalPaddingDp.dp,
                            end = settings.horizontalPaddingDp.dp,
                            top = settings.verticalPaddingDp.dp,
                            bottom = settings.verticalPaddingDp.dp + bottomSafeInset
                        )
                ) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (val p = pages[page]) {
                            is RichPage.Text -> {
                                PageTextView(
                                    text = p.content,
                                    startOffset = 0,
                                    endOffset = p.content.length,
                                    fontSizePx = fontPx,
                                    lineSpacingMultiplier = settings.lineSpacingMultiplier,
                                    textColor = textColor,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            is RichPage.Image -> {
                                EbookImageView(
                                    path = p.path,
                                    alt = p.alt,
                                    textColor = textColor,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp),
                                    maxHeightDp = 900
                                )
                            }
                        }
                    }
                    Text(
                        text = "${page + 1} / ${pages.size}",
                        color = textColor.copy(alpha = 0.45f),
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

@Composable
private fun EbookBlockItem(
    block: EbookBlock,
    settings: ReadingSettings,
    textColor: Color
) {
    when (block) {
        is EbookBlock.Heading -> {
            val size = when (block.level) {
                1 -> settings.fontSizeSp * 1.55f
                2 -> settings.fontSizeSp * 1.35f
                3 -> settings.fontSizeSp * 1.2f
                else -> settings.fontSizeSp * 1.08f
            }
            Text(
                text = block.text,
                color = textColor,
                fontSize = size.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                lineHeight = (size * settings.lineSpacingMultiplier).sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 8.dp)
            )
        }
        is EbookBlock.Paragraph -> {
            Text(
                text = block.text,
                color = textColor,
                fontSize = settings.fontSizeSp.sp,
                fontFamily = FontFamily.Serif,
                lineHeight = (settings.fontSizeSp * settings.lineSpacingMultiplier).sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            )
        }
        is EbookBlock.Bullet -> {
            Text(
                text = "•  ${block.text}",
                color = textColor,
                fontSize = settings.fontSizeSp.sp,
                fontFamily = FontFamily.Serif,
                lineHeight = (settings.fontSizeSp * settings.lineSpacingMultiplier).sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp, horizontal = 4.dp)
            )
        }
        is EbookBlock.Quote -> {
            Text(
                text = block.text,
                color = textColor.copy(alpha = 0.8f),
                fontSize = settings.fontSizeSp.sp,
                fontFamily = FontFamily.Serif,
                lineHeight = (settings.fontSizeSp * settings.lineSpacingMultiplier).sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp, horizontal = 12.dp)
                    .background(textColor.copy(alpha = 0.06f))
                    .padding(12.dp)
            )
        }
        is EbookBlock.Code -> {
            Text(
                text = block.text,
                color = textColor,
                fontSize = (settings.fontSizeSp * 0.9f).sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = (settings.fontSizeSp * 1.35f).sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .background(textColor.copy(alpha = 0.08f))
                    .padding(12.dp)
            )
        }
        is EbookBlock.Image -> {
            EbookImageView(
                path = block.path,
                alt = block.alt,
                textColor = textColor
            )
        }
        is EbookBlock.Divider -> {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .height(1.dp)
                    .background(textColor.copy(alpha = 0.2f))
            )
        }
    }
}

/**
 * 将块序列转为左右翻页页列表：文本用 StaticLayout 切页，图片独占一页。
 */
private fun buildRichPages(
    blocks: List<EbookBlock>,
    widthPx: Int,
    heightPx: Int,
    paint: android.text.TextPaint,
    lineSpacing: Float
): List<RichPage> {
    val result = mutableListOf<RichPage>()
    val textBuf = StringBuilder()
    var textStart = 0
    var hasTextStart = false

    fun flushText() {
        if (textBuf.isEmpty()) return
        val full = textBuf.toString()
        val start = if (hasTextStart) textStart else 0
        val breaks = TextPaginator.paginateChapter(
            text = full,
            chapterStart = 0,
            chapterEnd = full.length,
            widthPx = widthPx,
            heightPx = heightPx,
            paint = paint,
            lineSpacingMultiplier = lineSpacing
        )
        for (b in breaks) {
            result += RichPage.Text(
                content = full.substring(b.startOffset, b.endOffset),
                startOffset = start + b.startOffset
            )
        }
        textBuf.clear()
        hasTextStart = false
    }

    for (block in blocks) {
        when (block) {
            is EbookBlock.Image -> {
                flushText()
                result += RichPage.Image(block.path, block.alt, block.charOffset)
            }
            is EbookBlock.Heading -> {
                if (!hasTextStart) {
                    textStart = block.charOffset
                    hasTextStart = true
                }
                if (textBuf.isNotEmpty()) textBuf.append("\n\n")
                textBuf.append(block.text).append('\n')
            }
            is EbookBlock.Paragraph -> {
                if (!hasTextStart) {
                    textStart = block.charOffset
                    hasTextStart = true
                }
                if (textBuf.isNotEmpty()) textBuf.append("\n\n")
                textBuf.append(block.text)
            }
            is EbookBlock.Bullet -> {
                if (!hasTextStart) {
                    textStart = block.charOffset
                    hasTextStart = true
                }
                if (textBuf.isNotEmpty()) textBuf.append('\n')
                textBuf.append("• ").append(block.text)
            }
            is EbookBlock.Quote -> {
                if (!hasTextStart) {
                    textStart = block.charOffset
                    hasTextStart = true
                }
                if (textBuf.isNotEmpty()) textBuf.append("\n\n")
                textBuf.append(block.text)
            }
            is EbookBlock.Code -> {
                if (!hasTextStart) {
                    textStart = block.charOffset
                    hasTextStart = true
                }
                if (textBuf.isNotEmpty()) textBuf.append("\n\n")
                textBuf.append(block.text)
            }
            is EbookBlock.Divider -> {
                if (textBuf.isNotEmpty()) textBuf.append("\n\n")
            }
        }
    }
    flushText()
    if (result.isEmpty()) {
        result += RichPage.Text("", 0)
    }
    return result
}

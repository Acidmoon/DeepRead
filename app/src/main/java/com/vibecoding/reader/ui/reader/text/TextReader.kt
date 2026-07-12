package com.vibecoding.reader.ui.reader.text

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecoding.reader.domain.model.PageTurnMode
import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.ReadingSettings
import com.vibecoding.reader.domain.model.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TextReader(
    text: String,
    toc: List<TocEntry>,
    settings: ReadingSettings,
    initialPosition: String,
    jumpPosition: String?,
    onJumpConsumed: () -> Unit,
    onProgress: (position: String, progress: Float) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (settings.pageTurnMode == PageTurnMode.VERTICAL) {
        VerticalScrollReader(
            text = text,
            toc = toc,
            settings = settings,
            initialPosition = initialPosition,
            jumpPosition = jumpPosition,
            onJumpConsumed = onJumpConsumed,
            onProgress = onProgress,
            onToggleChrome = onToggleChrome,
            modifier = modifier
        )
    } else {
        PagedTextReader(
            text = text,
            toc = toc,
            settings = settings,
            initialPosition = initialPosition,
            jumpPosition = jumpPosition,
            onJumpConsumed = onJumpConsumed,
            onProgress = onProgress,
            onToggleChrome = onToggleChrome,
            modifier = modifier
        )
    }
}

/**
 * 左右翻页：只对当前章分页；StaticLayout 绘制；避免巨量 pageCount 的 Pager。
 */
@Composable
private fun PagedTextReader(
    text: String,
    toc: List<TocEntry>,
    settings: ReadingSettings,
    initialPosition: String,
    jumpPosition: String?,
    onJumpConsumed: () -> Unit,
    onProgress: (position: String, progress: Float) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val chapters = remember(text.length, toc) {
        TextPaginator.buildChapters(text.length, toc)
    }

    var chapterIndex by remember { mutableIntStateOf(0) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var pages by remember { mutableStateOf<List<TextPageBreak>>(emptyList()) }
    var paginating by remember { mutableStateOf(true) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    val hPadPx = with(density) { settings.horizontalPaddingDp.dp.roundToPx() }
    val vPadPx = with(density) { settings.verticalPaddingDp.dp.roundToPx() }
    val footerPx = with(density) { 22.dp.roundToPx() }
    val fontPx = with(density) { settings.fontSizeSp.sp.toPx() }
    val textPaint = remember(fontPx) { TextPaginator.createPaint(fontPx) }

    fun contentWidth(): Int = (viewport.width - hPadPx * 2).coerceAtLeast(1)
    fun contentHeight(): Int = (viewport.height - vPadPx * 2 - footerPx).coerceAtLeast(1)

    suspend fun buildPages(index: Int): List<TextPageBreak> {
        val ch = chapters.getOrNull(index) ?: return emptyList()
        textPaint.textSize = fontPx
        return withContext(Dispatchers.Default) {
            TextPaginator.paginateChapter(
                text = text,
                chapterStart = ch.start,
                chapterEnd = ch.end,
                widthPx = contentWidth(),
                heightPx = contentHeight(),
                paint = textPaint,
                lineSpacingMultiplier = settings.lineSpacingMultiplier
            )
        }
    }

    // 视口 / 版式变化：按当前偏移所在章重分页
    LaunchedEffect(
        text,
        chapters,
        viewport.width,
        viewport.height,
        settings.fontSizeSp,
        settings.lineSpacingMultiplier,
        settings.horizontalPaddingDp,
        settings.verticalPaddingDp
    ) {
        if (viewport.width <= 0 || viewport.height <= 0 || text.isEmpty()) {
            pages = emptyList()
            paginating = false
            return@LaunchedEffect
        }
        paginating = true
        val offset = pages.getOrNull(pageIndex)?.startOffset
            ?: (ReaderPosition.parse(initialPosition) as? ReaderPosition.CharOffset)?.offset
            ?: 0
        val chIdx = TextPaginator.chapterIndexForOffset(chapters, offset)
        chapterIndex = chIdx
        val result = buildPages(chIdx)
        pages = result
        pageIndex = TextPaginator.pageIndexForOffset(result, offset)
        paginating = false
    }

    // 目录 / 书签跳转
    LaunchedEffect(jumpPosition) {
        val jump = jumpPosition ?: return@LaunchedEffect
        val offset = (ReaderPosition.parse(jump) as? ReaderPosition.CharOffset)?.offset
        if (offset != null && viewport.width > 0 && text.isNotEmpty()) {
            paginating = true
            val chIdx = TextPaginator.chapterIndexForOffset(chapters, offset)
            chapterIndex = chIdx
            val result = buildPages(chIdx)
            pages = result
            pageIndex = TextPaginator.pageIndexForOffset(result, offset)
            paginating = false
        }
        onJumpConsumed()
    }

    LaunchedEffect(pageIndex, pages, text.length) {
        val page = pages.getOrNull(pageIndex) ?: return@LaunchedEffect
        onProgress(
            ReaderPosition.CharOffset(page.startOffset).serialize(),
            TextPaginator.progressForOffset(page.startOffset, text.length)
        )
    }

    val allowSlide = settings.pageTurnMode == PageTurnMode.SLIDE ||
        settings.pageTurnMode == PageTurnMode.BOTH
    val allowTap = settings.pageTurnMode == PageTurnMode.TAP ||
        settings.pageTurnMode == PageTurnMode.BOTH

    fun goPrev() {
        if (pageIndex > 0) {
            pageIndex--
            return
        }
        if (chapterIndex > 0) {
            scope.launch {
                paginating = true
                val prev = chapterIndex - 1
                val result = buildPages(prev)
                chapterIndex = prev
                pages = result
                pageIndex = (result.size - 1).coerceAtLeast(0)
                paginating = false
            }
        }
    }

    fun goNext() {
        if (pageIndex < pages.lastIndex) {
            pageIndex++
            return
        }
        if (chapterIndex < chapters.lastIndex) {
            scope.launch {
                paginating = true
                val next = chapterIndex + 1
                val result = buildPages(next)
                chapterIndex = next
                pages = result
                pageIndex = 0
                paginating = false
            }
        }
    }

    val page = pages.getOrNull(pageIndex)
    val chapter = chapters.getOrNull(chapterIndex)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(settings.backgroundColor))
            .onSizeChanged { viewport = it }
            .pointerInput(allowTap, pageIndex, pages.size, chapterIndex) {
                detectTapGestures { offset ->
                    if (!allowTap) {
                        onToggleChrome()
                        return@detectTapGestures
                    }
                    val w = size.width.toFloat()
                    when {
                        offset.x < w / 3f -> goPrev()
                        offset.x > w * 2f / 3f -> goNext()
                        else -> onToggleChrome()
                    }
                }
            }
            .then(
                if (allowSlide) {
                    Modifier.pointerInput(pageIndex, pages.size, chapterIndex) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragAccum = 0f },
                            onDragEnd = {
                                when {
                                    dragAccum > 80f -> goPrev()
                                    dragAccum < -80f -> goNext()
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
        when {
            paginating && pages.isEmpty() -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            page != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = settings.horizontalPaddingDp.dp,
                            vertical = settings.verticalPaddingDp.dp
                        )
                ) {
                    PageTextView(
                        text = text,
                        startOffset = page.startOffset,
                        endOffset = page.endOffset,
                        fontSizePx = fontPx,
                        lineSpacingMultiplier = settings.lineSpacingMultiplier,
                        textColor = Color(settings.textColor),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                    val label = buildString {
                        append(pageIndex + 1)
                        append(" / ")
                        append(pages.size.coerceAtLeast(1))
                        if (chapters.size > 1) {
                            append(" · ")
                            append(chapter?.title?.take(18).orEmpty())
                        }
                    }
                    Text(
                        text = label,
                        color = Color(settings.textColor).copy(alpha = 0.45f),
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
            else -> {
                Text(
                    "暂无内容",
                    color = Color(settings.textColor),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun VerticalScrollReader(
    text: String,
    toc: List<TocEntry>,
    settings: ReadingSettings,
    initialPosition: String,
    jumpPosition: String?,
    onJumpConsumed: () -> Unit,
    onProgress: (position: String, progress: Float) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chapters = remember(text.length, toc) {
        TextPaginator.buildChapters(text.length, toc)
    }
    var chapterIndex by remember {
        mutableIntStateOf(
            TextPaginator.chapterIndexForOffset(
                chapters,
                (ReaderPosition.parse(initialPosition) as? ReaderPosition.CharOffset)?.offset ?: 0
            )
        )
    }

    val chapter = chapters.getOrNull(chapterIndex)
    val blocks = remember(chapterIndex, text, chapters) {
        val ch = chapters.getOrNull(chapterIndex) ?: return@remember emptyList()
        TextPaginator.splitBlocks(text, ch.start, ch.end)
    }

    val listState = rememberLazyListState()
    var pendingScrollOffset by remember {
        mutableIntStateOf(
            (ReaderPosition.parse(initialPosition) as? ReaderPosition.CharOffset)?.offset ?: 0
        )
    }

    LaunchedEffect(blocks, pendingScrollOffset, chapterIndex) {
        if (blocks.isEmpty()) return@LaunchedEffect
        val target = pendingScrollOffset
        val idx = blocks.indexOfLast { it.startOffset <= target }.coerceAtLeast(0)
        listState.scrollToItem(idx)
    }

    LaunchedEffect(jumpPosition) {
        val jump = jumpPosition ?: return@LaunchedEffect
        val offset = (ReaderPosition.parse(jump) as? ReaderPosition.CharOffset)?.offset
        if (offset != null) {
            chapterIndex = TextPaginator.chapterIndexForOffset(chapters, offset)
            pendingScrollOffset = offset
        }
        onJumpConsumed()
    }

    LaunchedEffect(listState, blocks, text.length, chapterIndex) {
        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            val block = info?.index?.let { blocks.getOrNull(it) }
            block?.startOffset ?: chapter?.start ?: 0
        }
            .distinctUntilChanged()
            .sample(400)
            .collect { offset ->
                onProgress(
                    ReaderPosition.CharOffset(offset).serialize(),
                    TextPaginator.progressForOffset(offset, text.length)
                )
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(settings.backgroundColor))
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = settings.horizontalPaddingDp.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onToggleChrome() })
                }
        ) {
            if (chapters.size > 1) {
                item(key = "chapter-title") {
                    Text(
                        text = chapter?.title.orEmpty(),
                        color = Color(settings.textColor).copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 12.dp)
                    )
                }
            }

            items(items = blocks, key = { it.startOffset }) { block ->
                Text(
                    text = block.content,
                    color = Color(settings.textColor),
                    fontSize = settings.fontSizeSp.sp,
                    lineHeight = (settings.fontSizeSp * settings.lineSpacingMultiplier).sp,
                    fontFamily = FontFamily.Serif,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (chapters.size > 1) {
                item(key = "chapter-nav") {
                    Spacer(Modifier.height(28.dp))
                    // 左：上一章 · 右：下一章
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                if (chapterIndex > 0) {
                                    chapterIndex--
                                    pendingScrollOffset = chapters[chapterIndex].start
                                }
                            },
                            enabled = chapterIndex > 0
                        ) {
                            Text("‹ 上一章", color = Color(settings.textColor))
                        }
                        Text(
                            text = "${chapterIndex + 1} / ${chapters.size}",
                            color = Color(settings.textColor).copy(alpha = 0.45f),
                            fontSize = 12.sp
                        )
                        TextButton(
                            onClick = {
                                if (chapterIndex < chapters.lastIndex) {
                                    chapterIndex++
                                    pendingScrollOffset = chapters[chapterIndex].start
                                }
                            },
                            enabled = chapterIndex < chapters.lastIndex
                        ) {
                            Text("下一章 ›", color = Color(settings.textColor))
                        }
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }
}

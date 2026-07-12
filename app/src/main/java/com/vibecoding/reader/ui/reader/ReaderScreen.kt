package com.vibecoding.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Slider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibecoding.reader.domain.model.BookFormat
import com.vibecoding.reader.domain.model.Bookmark
import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.TocEntry
import com.vibecoding.reader.domain.model.TocKind
import com.vibecoding.reader.domain.reader.ScreenDim
import com.vibecoding.reader.domain.search.TextSearch
import com.vibecoding.reader.ui.reader.pdf.PdfReader
import com.vibecoding.reader.ui.reader.text.TextReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SheetKind {
    TOC,
    BOOKMARKS,
    SETTINGS,
    SEARCH,
    BRIGHTNESS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val jumpPosition by viewModel.jumpPosition.collectAsStateWithLifecycle()

    var showChrome by remember { mutableStateOf(false) }
    var sheetKind by remember { mutableStateOf<SheetKind?>(null) }
    var currentPosition by remember { mutableStateOf("") }
    var currentProgress by remember { mutableStateOf(0f) }
    var snackMsg by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(snackMsg) {
        snackMsg?.let {
            snackbar.showSnackbar(it)
            snackMsg = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (currentPosition.isNotBlank()) {
                viewModel.saveProgressNow(currentPosition, currentProgress)
            }
        }
    }

    val charOffset = (ReaderPosition.parse(currentPosition.ifBlank { content.initialPosition })
        as? ReaderPosition.CharOffset)?.offset
    val chapterNav = remember(content.toc, charOffset, book?.format) {
        resolveChapterNav(content.toc, charOffset)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                content.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                content.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(content.error ?: "错误")
                    }
                }
                book == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("书籍不存在")
                    }
                }
                else -> {
                    val dimAlpha = ScreenDim.overlayAlpha(settings.screenDim)
                    Box(Modifier.fillMaxSize()) {
                        // 内容区叠暗：draw 层不拦截触摸
                        Box(
                            Modifier
                                .fillMaxSize()
                                .drawWithContent {
                                    drawContent()
                                    if (dimAlpha > 0.001f) {
                                        drawRect(Color.Black.copy(alpha = dimAlpha))
                                    }
                                }
                        ) {
                            when (book!!.format) {
                                BookFormat.TXT, BookFormat.MD, BookFormat.EPUB, BookFormat.DOCX -> {
                                    TextReader(
                                        text = content.text,
                                        toc = content.toc,
                                        settings = settings,
                                        initialPosition = content.initialPosition,
                                        jumpPosition = jumpPosition,
                                        onJumpConsumed = viewModel::consumeJump,
                                        onProgress = { pos, progress ->
                                            currentPosition = pos
                                            currentProgress = progress
                                            viewModel.saveProgress(pos, progress)
                                        },
                                        onToggleChrome = { showChrome = !showChrome },
                                        modifier = Modifier.fillMaxSize(),
                                        blocks = content.blocks,
                                        markdownSource = content.markdownSource
                                    )
                                }
                                BookFormat.PDF -> {
                                    PdfReader(
                                        filePath = book!!.localPath,
                                        settings = settings,
                                        initialPosition = content.initialPosition,
                                        jumpPosition = jumpPosition,
                                        onJumpConsumed = viewModel::consumeJump,
                                        onProgress = { pos, progress, _, _ ->
                                            currentPosition = pos
                                            currentProgress = progress
                                            viewModel.saveProgress(pos, progress)
                                        },
                                        onToggleChrome = { showChrome = !showChrome },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }

                        if (showChrome) {
                            // 顶栏：返回 + 居中书名 + 进度
                            ReaderTopChrome(
                                title = book?.title ?: "阅读",
                                progressPercent = currentProgress,
                                onBack = {
                                    if (currentPosition.isNotBlank()) {
                                        viewModel.saveProgressNow(
                                            currentPosition,
                                            currentProgress
                                        )
                                    }
                                    onBack()
                                },
                                modifier = Modifier.align(Alignment.TopCenter)
                            )

                            // 底栏：上一章 / 下一章 + 目录 · 书签 · 设置
                            ReaderBottomChrome(
                                hasChapters = book!!.format.isEbook &&
                                    content.toc.size >= 2,
                                canPrevChapter = chapterNav.prev != null,
                                canNextChapter = chapterNav.next != null,
                                onPrevChapter = {
                                    chapterNav.prev?.let {
                                        viewModel.jumpTo(it.position)
                                    }
                                },
                                onNextChapter = {
                                    chapterNav.next?.let {
                                        viewModel.jumpTo(it.position)
                                    }
                                },
                                onOpenToc = {
                                    sheetKind = SheetKind.TOC
                                },
                                onOpenBookmarks = {
                                    sheetKind = SheetKind.BOOKMARKS
                                },
                                onAddBookmark = {
                                    val pos = currentPosition.ifBlank { content.initialPosition }
                                    if (pos.isBlank()) {
                                        snackMsg = "暂无位置可收藏"
                                        return@ReaderBottomChrome
                                    }
                                    val label = when (val p = ReaderPosition.parse(pos)) {
                                        is ReaderPosition.CharOffset ->
                                            viewModel.excerptForText(p.offset)
                                        is ReaderPosition.PageIndex ->
                                            "第 ${p.page + 1} 页"
                                        null -> "书签"
                                    }
                                    viewModel.addBookmark(pos, label)
                                    snackMsg = "已添加书签"
                                },
                                onOpenSettings = {
                                    sheetKind = SheetKind.SETTINGS
                                },
                                showSearch = book!!.format.isEbook,
                                onOpenSearch = {
                                    sheetKind = SheetKind.SEARCH
                                },
                                onOpenBrightness = {
                                    sheetKind = SheetKind.BRIGHTNESS
                                },
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }
                    }
                }
            }
        }
    }

    when (val kind = sheetKind) {
        SheetKind.SETTINGS -> {
            ModalBottomSheet(
                onDismissRequest = { sheetKind = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                ReaderSettingsPanel(
                    settings = settings,
                    format = book?.format ?: BookFormat.TXT,
                    onChange = viewModel::updateSettings
                )
                Spacer(Modifier.height(24.dp))
            }
        }
        SheetKind.SEARCH -> {
            ModalBottomSheet(
                onDismissRequest = { sheetKind = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                SearchSheet(
                    text = content.text,
                    onHit = { hit ->
                        viewModel.jumpTo(hit.position)
                        sheetKind = null
                        showChrome = false
                    }
                )
                Spacer(Modifier.height(24.dp))
            }
        }
        SheetKind.BRIGHTNESS -> {
            ModalBottomSheet(
                onDismissRequest = { sheetKind = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                BrightnessSheet(
                    screenDim = settings.screenDim,
                    onChange = { dim ->
                        viewModel.updateSettings(
                            settings.copy(screenDim = ScreenDim.clamp(dim))
                        )
                    }
                )
                Spacer(Modifier.height(24.dp))
            }
        }
        SheetKind.TOC, SheetKind.BOOKMARKS -> {
            ModalBottomSheet(
                onDismissRequest = { sheetKind = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                PanelSheet(
                    initialTab = if (kind == SheetKind.TOC) 0 else 1,
                    toc = content.toc,
                    bookmarks = bookmarks,
                    currentPosition = currentPosition.ifBlank { content.initialPosition },
                    onTocClick = {
                        viewModel.jumpTo(it.position)
                        sheetKind = null
                        showChrome = false
                    },
                    onBookmarkClick = {
                        viewModel.jumpTo(it.position)
                        sheetKind = null
                        showChrome = false
                    },
                    onDeleteBookmark = viewModel::deleteBookmark
                )
            }
        }
        null -> Unit
    }
}

/**
 * 顶栏：左侧返回，中间居中书名，下方细进度；不把功能按钮堆在右上角。
 */
@Composable
private fun ReaderTopChrome(
    title: String,
    progressPercent: Float,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(bottom = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 4.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回"
                    )
                }
                // 书名真正居中（不挤在返回键右侧）
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 52.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${(progressPercent.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 底栏：第一行上一章/下一章，第二行目录、书签、加书签、设置。
 */
@Composable
private fun ReaderBottomChrome(
    hasChapters: Boolean,
    canPrevChapter: Boolean,
    canNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onAddBookmark: () -> Unit,
    onOpenSettings: () -> Unit,
    showSearch: Boolean = false,
    onOpenSearch: () -> Unit = {},
    onOpenBrightness: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            if (hasChapters) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onPrevChapter,
                        enabled = canPrevChapter,
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp,
                            vertical = 4.dp
                        )
                    ) {
                        Icon(
                            Icons.Default.ChevronLeft,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Text("上一章", fontSize = 14.sp)
                    }
                    TextButton(
                        onClick = onNextChapter,
                        enabled = canNextChapter,
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp,
                            vertical = 4.dp
                        )
                    ) {
                        Text("下一章", fontSize = 14.sp)
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomAction(
                    icon = Icons.AutoMirrored.Filled.List,
                    label = "目录",
                    onClick = onOpenToc
                )
                if (showSearch) {
                    BottomAction(
                        icon = Icons.Default.Search,
                        label = "搜索",
                        onClick = onOpenSearch
                    )
                }
                BottomAction(
                    icon = Icons.Default.Bookmark,
                    label = "书签",
                    onClick = onOpenBookmarks
                )
                BottomAction(
                    icon = Icons.Default.BookmarkAdd,
                    label = "加书签",
                    onClick = onAddBookmark
                )
                BottomAction(
                    icon = Icons.Default.BrightnessMedium,
                    label = "亮度",
                    onClick = onOpenBrightness
                )
                BottomAction(
                    icon = Icons.Default.Settings,
                    label = "设置",
                    onClick = onOpenSettings
                )
            }
        }
    }
}

@Composable
private fun BrightnessSheet(
    screenDim: Float,
    onChange: (Float) -> Unit
) {
    val percent = ScreenDim.toPercent(screenDim)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Text("阅读亮度（应用内）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "当前约 $percent%（不修改系统亮度，夜间可调暗护眼）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(16.dp))
        Slider(
            value = percent.toFloat(),
            onValueChange = { p ->
                onChange(ScreenDim.fromBrightnessPercent(p.toInt()))
            },
            valueRange = 30f..100f
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("暗", style = MaterialTheme.typography.labelMedium)
            Text("亮", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SearchSheet(
    text: String,
    onHit: (TextSearch.Hit) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TextSearch.Hit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(460.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("全文搜索", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("关键词") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(
                    onClick = {
                        scope.launch {
                            searching = true
                            hits = withContext(Dispatchers.Default) {
                                TextSearch.search(text, query)
                            }
                            searching = false
                        }
                    }
                ) {
                    Icon(Icons.Default.Search, contentDescription = "搜索")
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                scope.launch {
                    searching = true
                    hits = withContext(Dispatchers.Default) {
                        TextSearch.search(text, query)
                    }
                    searching = false
                }
            },
            enabled = query.isNotBlank() && !searching
        ) { Text(if (searching) "搜索中…" else "搜索") }

        Spacer(Modifier.height(8.dp))
        Text(
            when {
                searching -> "正在搜索…"
                query.isBlank() -> "输入关键词后点击搜索"
                hits.isEmpty() -> "无匹配结果"
                else -> "共 ${hits.size} 处（最多显示 100）"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(hits, key = { "${it.offset}-${it.snippet}" }) { hit ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onHit(hit) }
                        .padding(vertical = 12.dp)
                ) {
                    Text(
                        hit.snippet,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "位置 ${hit.offset}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f))
            }
        }
    }
}

@Composable
private fun BottomAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .width(72.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )
    }
}

private data class ChapterNav(
    val prev: TocEntry?,
    val next: TocEntry?,
    val current: TocEntry?
)

/**
 * 上一章/下一章只在「章」之间跳转，跳过卷标题。
 */
private fun resolveChapterNav(toc: List<TocEntry>, offset: Int?): ChapterNav {
    if (toc.isEmpty() || offset == null) {
        return ChapterNav(null, null, null)
    }
    val chapters = toc.mapNotNull { entry ->
        if (entry.kind == TocKind.VOLUME) return@mapNotNull null
        val o = (ReaderPosition.parse(entry.position) as? ReaderPosition.CharOffset)?.offset
            ?: return@mapNotNull null
        entry to o
    }.sortedBy { it.second }

    // 若没有任何章条目，退回全部（兼容异常缓存）
    val starts = chapters.ifEmpty {
        toc.mapNotNull { entry ->
            val o = (ReaderPosition.parse(entry.position) as? ReaderPosition.CharOffset)?.offset
                ?: return@mapNotNull null
            entry to o
        }.sortedBy { it.second }
    }
    if (starts.isEmpty()) return ChapterNav(null, null, null)

    var idx = starts.indexOfLast { it.second <= offset }
    if (idx < 0) idx = 0
    return ChapterNav(
        prev = starts.getOrNull(idx - 1)?.first,
        next = starts.getOrNull(idx + 1)?.first,
        current = starts.getOrNull(idx)?.first
    )
}

/** 当前阅读位置对应的目录项下标（含卷），用于目录列表定位。 */
private fun indexOfCurrentTocEntry(toc: List<TocEntry>, position: String?): Int {
    if (toc.isEmpty()) return 0
    val offset = (ReaderPosition.parse(position) as? ReaderPosition.CharOffset)?.offset
        ?: return 0
    val starts = toc.mapNotNull { entry ->
        val o = (ReaderPosition.parse(entry.position) as? ReaderPosition.CharOffset)?.offset
            ?: return@mapNotNull null
        o
    }
    if (starts.isEmpty()) return 0
    var idx = starts.indexOfLast { it <= offset }
    if (idx < 0) idx = 0
    return idx.coerceIn(0, toc.lastIndex)
}

@Composable
private fun PanelSheet(
    initialTab: Int,
    toc: List<TocEntry>,
    bookmarks: List<Bookmark>,
    currentPosition: String,
    onTocClick: (TocEntry) -> Unit,
    onBookmarkClick: (Bookmark) -> Unit,
    onDeleteBookmark: (String) -> Unit
) {
    var tab by remember { mutableIntStateOf(initialTab) }
    val currentIndex = remember(toc, currentPosition) {
        indexOfCurrentTocEntry(toc, currentPosition)
    }
    val listState = rememberLazyListState()

    // 打开目录时滚到当前章节（尽量居中）
    LaunchedEffect(tab, toc, currentIndex) {
        if (tab == 0 && toc.isNotEmpty()) {
            val target = currentIndex.coerceIn(0, toc.lastIndex)
            // 略微提前一点，让当前章出现在可视区中部
            val scrollTo = (target - 3).coerceAtLeast(0)
            listState.scrollToItem(scrollTo)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(460.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SheetTab(
                selected = tab == 0,
                label = "目录",
                icon = Icons.AutoMirrored.Filled.MenuBook,
                onClick = { tab = 0 },
                modifier = Modifier.weight(1f)
            )
            SheetTab(
                selected = tab == 1,
                label = "书签",
                icon = Icons.Default.Bookmark,
                onClick = { tab = 1 },
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider()
        when (tab) {
            0 -> {
                if (toc.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "暂无目录\n（仅识别「第X章 / Chapter X」与「第X卷 / Volume X」）",
                            color = MaterialTheme.colorScheme.onSurface.copy(0.55f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = toc,
                            key = { index, entry -> "${entry.position}|${entry.title}|$index" }
                        ) { index, entry ->
                            val selected = index == currentIndex
                            val bg = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                            } else {
                                Color.Transparent
                            }
                            val titleColor = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                            Text(
                                text = entry.title,
                                color = titleColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bg)
                                    .clickable { onTocClick(entry) }
                                    .padding(
                                        start = (16 + entry.level * 16).dp,
                                        end = 16.dp,
                                        top = 12.dp,
                                        bottom = 12.dp
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = when {
                                    selected -> FontWeight.SemiBold
                                    entry.kind == TocKind.VOLUME -> FontWeight.Bold
                                    entry.level == 0 -> FontWeight.Medium
                                    else -> FontWeight.Normal
                                }
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f)
                            )
                        }
                    }
                }
            }
            else -> {
                if (bookmarks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无书签", color = MaterialTheme.colorScheme.onSurface.copy(0.55f))
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(bookmarks, key = { it.id }) { bm ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = bm.label ?: bm.position,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onBookmarkClick(bm) }
                                        .padding(12.dp)
                                )
                                IconButton(onClick = { onDeleteBookmark(bm.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除书签")
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTab(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontWeight = FontWeight.Medium)
    }
}

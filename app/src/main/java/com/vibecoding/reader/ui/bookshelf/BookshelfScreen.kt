package com.vibecoding.reader.ui.bookshelf

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibecoding.reader.data.import.CoverGenerator
import com.vibecoding.reader.domain.model.Book
import com.vibecoding.reader.domain.model.BookFolder
import com.vibecoding.reader.domain.model.BookFormat
import com.vibecoding.reader.domain.model.ShelfItem
import java.io.File

private val importMimeTypes = arrayOf(
    "text/plain",
    "text/markdown",
    "text/x-markdown",
    "application/epub+zip",
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/msword",
    "*/*"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    onOpenBook: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onBackFromFolder: (() -> Unit)? = null,
    rootFolders: List<BookFolder> = emptyList(),
    /** 嵌入首页 Tab 时：根书架不显示独立顶栏（由首页 Tab 顶栏替代） */
    embeddedInHome: Boolean = false
) {
    val shelfItems by viewModel.shelfItems.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var pendingDeleteBook by remember { mutableStateOf<Book?>(null) }
    var pendingFolderAction by remember { mutableStateOf<BookFolder?>(null) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<BookFolder?>(null) }
    var moveBook by remember { mutableStateOf<Book?>(null) }
    var fabMenu by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.import(uris) }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.importFromTree(uri)
    }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val inFolder = ui.currentFolderId != null

    val showTopBar = inFolder || !embeddedInHome

    Scaffold(
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (inFolder) ui.currentFolderName ?: "文件夹"
                                else "我的书架",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (inFolder) "文件夹内 · 可继续导入"
                                else "本地阅读 · 文件夹与电子书",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    },
                    navigationIcon = {
                        if (inFolder && onBackFromFolder != null) {
                            IconButton(onClick = onBackFromFolder) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回书架"
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabMenu = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加")
                }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("导入文件") },
                        onClick = {
                            fabMenu = false
                            picker.launch(importMimeTypes)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Add, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (inFolder) "从系统文件夹导入到此处"
                                else "从系统文件夹导入"
                            )
                        },
                        onClick = {
                            fabMenu = false
                            treePicker.launch(null)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                        }
                    )
                    if (!inFolder) {
                        DropdownMenuItem(
                            text = { Text("新建空文件夹") },
                            onClick = {
                                fabMenu = false
                                showCreateFolder = true
                            },
                            leadingIcon = {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                            }
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (shelfItems.isEmpty() && !ui.isImporting) {
                EmptyBookshelf(
                    inFolder = inFolder,
                    modifier = Modifier.align(Alignment.Center),
                    onImport = { picker.launch(importMimeTypes) },
                    onCreateFolder = if (!inFolder) {
                        { showCreateFolder = true }
                    } else null
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        shelfItems,
                        key = {
                            when (it) {
                                is ShelfItem.Folder -> "f-${it.folder.id}"
                                is ShelfItem.BookItem -> "b-${it.book.id}"
                            }
                        }
                    ) { item ->
                        when (item) {
                            is ShelfItem.Folder -> {
                                FolderCard(
                                    item = item,
                                    onClick = { onOpenFolder(item.folder.id) },
                                    onLongClick = { pendingFolderAction = item.folder }
                                )
                            }
                            is ShelfItem.BookItem -> {
                                ShelfBookCard(
                                    book = item.book,
                                    onClick = { onOpenBook(item.book.id) },
                                    onLongClick = {
                                        // 根目录长按：删除 / 移入文件夹；文件夹内：删除 / 移出
                                        pendingDeleteBook = item.book
                                    },
                                    onMove = {
                                        moveBook = item.book
                                    },
                                    showMove = true
                                )
                            }
                        }
                    }
                }
            }

            if (ui.isImporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("正在导入…")
                        }
                    }
                }
            }
        }
    }

    // 删除书籍
    pendingDeleteBook?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDeleteBook = null },
            title = { Text(book.title) },
            text = {
                Column {
                    TextButton(onClick = {
                        moveBook = book
                        pendingDeleteBook = null
                    }) { Text(if (inFolder) "移出文件夹 / 移动…" else "移入文件夹…") }
                    Text("或删除此书？书签与本地副本将一并清除。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(book)
                    pendingDeleteBook = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteBook = null }) { Text("取消") }
            }
        )
    }

    // 文件夹操作
    pendingFolderAction?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingFolderAction = null },
            title = { Text(folder.name) },
            text = { Text("管理此文件夹") },
            confirmButton = {
                TextButton(onClick = {
                    renameFolder = folder
                    pendingFolderAction = null
                }) { Text("重命名") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.deleteFolder(folder, deleteBooks = false)
                        pendingFolderAction = null
                    }) { Text("删除(书籍保留)") }
                    TextButton(onClick = {
                        viewModel.deleteFolder(folder, deleteBooks = true)
                        pendingFolderAction = null
                    }) { Text("删除全部") }
                    TextButton(onClick = { pendingFolderAction = null }) { Text("取消") }
                }
            }
        )
    }

    if (showCreateFolder) {
        FolderNameDialog(
            title = "新建文件夹",
            initial = "",
            onDismiss = { showCreateFolder = false },
            onConfirm = {
                viewModel.createFolder(it)
                showCreateFolder = false
            }
        )
    }

    renameFolder?.let { folder ->
        FolderNameDialog(
            title = "重命名文件夹",
            initial = folder.name,
            onDismiss = { renameFolder = null },
            onConfirm = {
                viewModel.renameFolder(folder, it)
                renameFolder = null
            }
        )
    }

    moveBook?.let { book ->
        MoveBookDialog(
            book = book,
            folders = rootFolders,
            currentFolderId = ui.currentFolderId,
            onDismiss = { moveBook = null },
            onMove = { targetId ->
                viewModel.moveBookToFolder(book, targetId)
                moveBook = null
            }
        )
    }
}

@Composable
private fun FolderNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun MoveBookDialog(
    book: Book,
    folders: List<BookFolder>,
    currentFolderId: String?,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动「${book.title}」") },
        text = {
            Column {
                if (currentFolderId != null) {
                    TextButton(onClick = { onMove(null) }) {
                        Text("移到根书架")
                    }
                }
                if (folders.isEmpty()) {
                    Text("暂无文件夹，请先在根书架新建。")
                } else {
                    folders.forEach { f ->
                        if (f.id != currentFolderId) {
                            TextButton(onClick = { onMove(f.id) }) {
                                Text(f.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun EmptyBookshelf(
    inFolder: Boolean,
    modifier: Modifier = Modifier,
    onImport: () -> Unit,
    onCreateFolder: (() -> Unit)?
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            if (inFolder) Icons.Default.Folder else Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (inFolder) "文件夹还是空的" else "书架还是空的",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (inFolder) "可导入文件，或从系统文件夹批量导入到此处"
            else "可导入文件 / 从系统文件夹导入，或新建空文件夹",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onImport) { Text("导入文件") }
        if (onCreateFolder != null) {
            TextButton(onClick = onCreateFolder) { Text("新建空文件夹") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    item: ShelfItem.Folder,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFFFBBF24), Color(0xFFD97706))
                        )
                    )
            ) {
                // 封面预览拼贴
                val covers = item.previewCoverPaths
                if (covers.isNotEmpty()) {
                    FolderCoverCollage(
                        paths = covers,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(72.dp)
                    )
                }
                Text(
                    text = "文件夹",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    item.folder.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${item.bookCount} 本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@Composable
private fun FolderCoverCollage(
    paths: List<String>,
    modifier: Modifier = Modifier
) {
    val bitmaps = remember(paths) {
        paths.take(4).mapNotNull { p ->
            runCatching {
                BitmapFactory.decodeFile(p)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.2f))
    ) {
        when (bitmaps.size) {
            0 -> Unit
            1 -> Image(
                bitmap = bitmaps[0],
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            else -> {
                // 2x2
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        bitmaps.getOrNull(0)?.let {
                            Image(
                                bitmap = it,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.weight(1f).fillMaxSize().padding(1.dp)
                            )
                        }
                        bitmaps.getOrNull(1)?.let {
                            Image(
                                bitmap = it,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.weight(1f).fillMaxSize().padding(1.dp)
                            )
                        }
                    }
                    if (bitmaps.size > 2) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            bitmaps.getOrNull(2)?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).fillMaxSize().padding(1.dp)
                                )
                            }
                            bitmaps.getOrNull(3)?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).fillMaxSize().padding(1.dp)
                                )
                            } ?: Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfBookCard(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onMove: () -> Unit = {},
    showMove: Boolean = false
) {
    val bookDir = remember(book.localPath) { CoverGenerator.bookDirOf(book.localPath) }
    val coverFile = remember(book.coverPath, bookDir) {
        when {
            !book.coverPath.isNullOrBlank() && File(book.coverPath).exists() ->
                File(book.coverPath)
            bookDir != null && CoverGenerator.coverFile(bookDir).exists() ->
                CoverGenerator.coverFile(bookDir)
            else -> null
        }
    }
    val excerpt = remember(book.id, bookDir, book.coverPath) {
        CoverGenerator.loadExcerpt(bookDir)
    }
    val coverBitmap = remember(coverFile?.absolutePath, coverFile?.lastModified()) {
        coverFile?.let { file ->
            runCatching {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                when {
                    coverBitmap != null -> {
                        Image(
                            bitmap = coverBitmap,
                            contentDescription = "${book.title} 封面",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                                    )
                                )
                        )
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp)
                        )
                    }
                    !excerpt.isNullOrBlank() && book.format.isEbook -> {
                        TextExcerptCover(
                            title = book.title,
                            excerpt = excerpt,
                            format = book.format,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        FallbackCover(
                            title = book.title,
                            format = book.format,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                FormatBadge(
                    format = book.format,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    book.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { book.progressPercent.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${(book.progressPercent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@Composable
private fun TextExcerptCover(
    title: String,
    excerpt: String,
    format: BookFormat,
    modifier: Modifier = Modifier
) {
    val bg = when (format) {
        BookFormat.TXT -> listOf(Color(0xFFEEF4FF), Color(0xFFD6E6FF))
        BookFormat.MD -> listOf(Color(0xFFF3EEFF), Color(0xFFE4D9FF))
        BookFormat.EPUB -> listOf(Color(0xFFFFF1E8), Color(0xFFFFDCC8))
        BookFormat.DOCX -> listOf(Color(0xFFE8F7FC), Color(0xFFCDECF7))
        else -> listOf(Color(0xFFF5F5F5), Color(0xFFE0E0E0))
    }
    Box(
        modifier = modifier.background(Brush.verticalGradient(bg))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFFFFCF5))
                .padding(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color(0x332563EB))
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = excerpt,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Serif,
                color = Color(0xFF334155),
                lineHeight = 16.sp,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun FallbackCover(
    title: String,
    format: BookFormat,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(coverBrush(format))
            .padding(14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

@Composable
private fun FormatBadge(
    format: BookFormat,
    modifier: Modifier = Modifier
) {
    Text(
        text = format.displayLabel,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.95f),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

private fun coverBrush(format: BookFormat): Brush {
    val colors = when (format) {
        BookFormat.TXT -> listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8))
        BookFormat.MD -> listOf(Color(0xFF8B5CF6), Color(0xFF6D28D9))
        BookFormat.EPUB -> listOf(Color(0xFFF97316), Color(0xFFC2410C))
        BookFormat.PDF -> listOf(Color(0xFFEF4444), Color(0xFFB91C1C))
        BookFormat.DOCX -> listOf(Color(0xFF0EA5E9), Color(0xFF0369A1))
    }
    return Brush.linearGradient(colors)
}

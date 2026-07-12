package com.vibecoding.reader.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vibecoding.reader.di.AppContainer
import com.vibecoding.reader.domain.model.Book
import com.vibecoding.reader.domain.model.BookFolder
import com.vibecoding.reader.ui.bookshelf.BookshelfScreen
import com.vibecoding.reader.ui.bookshelf.BookshelfViewModel
import com.vibecoding.reader.ui.bookshelf.ShelfBookCard
import com.vibecoding.reader.ui.common.AppBottomStatusBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    rootFolders: List<BookFolder>,
    onOpenBook: (String) -> Unit,
    onOpenFolder: (String) -> Unit
) {
    val tabs = listOf("最近阅读", "我的书架")
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    // ViewModels 提到 Pager 外，避免左右滑时重复创建
    val recentVm: RecentViewModel = viewModel(
        factory = RecentViewModel.Factory(container.bookRepository)
    )
    val shelfVm: BookshelfViewModel = viewModel(
        key = "home-shelf-root",
        factory = BookshelfViewModel.Factory(
            bookRepository = container.bookRepository,
            folderRepository = container.folderRepository,
            shelfRepository = container.shelfRepository,
            bookImporter = container.bookImporter,
            folderId = null
        )
    )
    val recent by recentVm.recentBooks.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            text = {
                                Text(
                                    text = title,
                                    fontWeight = if (pagerState.currentPage == index) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        // 内容全屏；底部状态叠在内容之上（不占 Scaffold bottomBar 条）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = true,
                beyondViewportPageCount = 1
            ) { page ->
                when (page) {
                    0 -> RecentReadingContent(
                        books = recent,
                        onOpenBook = onOpenBook,
                        onGoShelf = {
                            scope.launch { pagerState.animateScrollToPage(1) }
                        }
                    )
                    else -> BookshelfScreen(
                        viewModel = shelfVm,
                        onOpenBook = onOpenBook,
                        onOpenFolder = onOpenFolder,
                        rootFolders = rootFolders,
                        embeddedInHome = true
                    )
                }
            }
            AppBottomStatusBar(
                progressPercent = null,
                onBackground = MaterialTheme.colorScheme.surface,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun RecentReadingContent(
    books: List<Book>,
    onOpenBook: (String) -> Unit,
    onGoShelf: () -> Unit
) {
    if (books.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("还没有最近阅读", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "打开任意书籍后，会出现在这里（最多 6 本）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onGoShelf) { Text("去我的书架") }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(books, key = { it.id }) { book ->
                ShelfBookCard(
                    book = book,
                    onClick = { onOpenBook(book.id) }
                )
            }
        }
    }
}

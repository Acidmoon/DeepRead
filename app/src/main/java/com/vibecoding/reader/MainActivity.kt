package com.vibecoding.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vibecoding.reader.domain.reader.ExternalOpenIntent
import com.vibecoding.reader.ui.bookshelf.BookshelfScreen
import com.vibecoding.reader.ui.bookshelf.BookshelfViewModel
import com.vibecoding.reader.ui.common.HideSystemStatusBar
import com.vibecoding.reader.ui.common.applyImmersiveStatusBarHidden
import com.vibecoding.reader.ui.home.HomeScreen
import com.vibecoding.reader.ui.reader.ReaderScreen
import com.vibecoding.reader.ui.reader.ReaderViewModel
import com.vibecoding.reader.ui.theme.ReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 全应用隐藏顶部状态栏
        applyImmersiveStatusBarHidden()
        val app = application as ReaderApp
        // 冷启动时处理 VIEW；配置恢复时导航栈会还原，避免重复导入
        if (savedInstanceState == null) {
            offerViewIntent(intent, app)
        }
        setContent {
            ReaderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HideSystemStatusBar(hideStatusBar = true)
                    ReaderNavHost(app)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerViewIntent(intent, application as ReaderApp)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveStatusBarHidden()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveStatusBarHidden()
    }

    private fun offerViewIntent(intent: Intent?, app: ReaderApp) {
        val data = ExternalOpenIntent.dataUri(intent?.action, intent?.dataString) ?: return
        app.container.externalOpen.offer(Uri.parse(data))
    }

    private fun applyImmersiveStatusBarHidden() {
        window.applyImmersiveStatusBarHidden()
    }
}

@Composable
private fun ReaderNavHost(app: ReaderApp) {
    val navController = rememberNavController()
    val container = app.container
    val context = LocalContext.current
    val folders by container.folderRepository.observeFolders()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingUri by container.externalOpen.pendingUri.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var openingExternal by remember { mutableStateOf(false) }

    LaunchedEffect(pendingUri) {
        val uri = pendingUri ?: return@LaunchedEffect
        openingExternal = true
        val result = withContext(Dispatchers.IO) {
            runCatching { container.bookImporter.importOrReuse(uri) }
        }
        container.externalOpen.consume()
        openingExternal = false
        result.onSuccess { outcome ->
            navController.navigate("reader/${outcome.book.id}")
        }.onFailure { e ->
            snackbar.showSnackbar(e.message ?: "无法打开文件")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "home"
        ) {
            composable("home") {
                HomeScreen(
                    container = container,
                    rootFolders = folders,
                    onOpenBook = { id -> navController.navigate("reader/$id") },
                    onOpenFolder = { id -> navController.navigate("folder/$id") }
                )
            }
            composable(
                route = "folder/{folderId}",
                arguments = listOf(navArgument("folderId") { type = NavType.StringType })
            ) { entry ->
                val folderId = entry.arguments?.getString("folderId") ?: return@composable
                val vm: BookshelfViewModel = viewModel(
                    key = "folder-$folderId",
                    factory = BookshelfViewModel.Factory(
                        bookRepository = container.bookRepository,
                        folderRepository = container.folderRepository,
                        shelfRepository = container.shelfRepository,
                        bookImporter = container.bookImporter,
                        folderId = folderId
                    )
                )
                BookshelfScreen(
                    viewModel = vm,
                    onOpenBook = { id -> navController.navigate("reader/$id") },
                    onOpenFolder = { },
                    onBackFromFolder = { navController.popBackStack() },
                    rootFolders = folders,
                    embeddedInHome = false
                )
            }
            composable(
                route = "reader/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
            ) { entry ->
                val bookId = entry.arguments?.getString("bookId") ?: return@composable
                val vm: ReaderViewModel = viewModel(
                    key = "reader-$bookId",
                    factory = ReaderViewModel.Factory(
                        bookId = bookId,
                        appContext = context.applicationContext,
                        bookRepository = container.bookRepository,
                        bookmarkRepository = container.bookmarkRepository,
                        settingsRepository = container.settingsRepository
                    )
                )
                ReaderScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )

        if (openingExternal) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

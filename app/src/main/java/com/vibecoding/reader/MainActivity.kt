package com.vibecoding.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vibecoding.reader.ui.bookshelf.BookshelfScreen
import com.vibecoding.reader.ui.bookshelf.BookshelfViewModel
import com.vibecoding.reader.ui.common.HideSystemStatusBar
import com.vibecoding.reader.ui.common.applyImmersiveStatusBarHidden
import com.vibecoding.reader.ui.home.HomeScreen
import com.vibecoding.reader.ui.reader.ReaderScreen
import com.vibecoding.reader.ui.reader.ReaderViewModel
import com.vibecoding.reader.ui.theme.ReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 全应用隐藏顶部状态栏
        applyImmersiveStatusBarHidden()
        val app = application as ReaderApp
        setContent {
            ReaderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HideSystemStatusBar(hideStatusBar = true)
                    ReaderNavHost(app)
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveStatusBarHidden()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveStatusBarHidden()
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
}

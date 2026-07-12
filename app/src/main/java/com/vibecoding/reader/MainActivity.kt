package com.vibecoding.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vibecoding.reader.ui.bookshelf.BookshelfScreen
import com.vibecoding.reader.ui.bookshelf.BookshelfViewModel
import com.vibecoding.reader.ui.reader.ReaderScreen
import com.vibecoding.reader.ui.reader.ReaderViewModel
import com.vibecoding.reader.ui.theme.ReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ReaderApp
        setContent {
            ReaderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReaderNavHost(app)
                }
            }
        }
    }
}

@Composable
private fun ReaderNavHost(app: ReaderApp) {
    val navController = rememberNavController()
    val container = app.container
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = "bookshelf"
    ) {
        composable("bookshelf") {
            val vm: BookshelfViewModel = viewModel(
                factory = BookshelfViewModel.Factory(
                    container.bookRepository,
                    container.bookImporter
                )
            )
            BookshelfScreen(
                viewModel = vm,
                onOpenBook = { id -> navController.navigate("reader/$id") }
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

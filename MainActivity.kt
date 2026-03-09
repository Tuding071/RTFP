package com.rtfp.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

class MainActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get initial video path from intent (if opened from file manager)
        val initialVideoPath = extractVideoPath(intent)
        
        // Setup fullscreen immersive mode
        setupFullscreen()
        
        // Keep screen on while activity is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContent {
            MaterialTheme {
                // ✅ STATE MANAGED IN COMPOSE - survives recomposition!
                var videoPath by remember { mutableStateOf(initialVideoPath) }
                var showFileManager by remember { mutableStateOf(initialVideoPath == null) }
                
                // ✅ BACK BUTTON HANDLER - using Compose state
                DisposableEffect(showFileManager) {
                    val callback = object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            if (showFileManager) {
                                finish() // Exit app if on file manager
                            } else {
                                // Go back to file manager
                                showFileManager = true
                                videoPath = null
                            }
                        }
                    }
                    onBackPressedDispatcher.addCallback(callback)
                    
                    onDispose {
                        callback.remove()
                    }
                }
                
                if (showFileManager) {
                    FileManagerScreen(
                        onFileSelected = { path ->
                            // ✅ FIX: Convert file path to proper URI format
                            videoPath = "file://$path"
                            showFileManager = false
                            // ✅ NO recreate() needed - just update state!
                        }
                    )
                } else {
                    PlayerScreen(
                        videoPath = videoPath,
                        onVideoLoaded = { width, height ->
                            setOrientationForVideo(width, height)
                        }
                    )
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // New video opened from outside - recreate to reset state
        recreate()
    }
    
    private fun extractVideoPath(intent: Intent?): String? {
        return when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString()
            else -> null
        }
    }
    
    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    
    private fun setOrientationForVideo(width: Int, height: Int) {
        requestedOrientation = if (width > height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    
    override fun onResume() {
        super.onResume()
        setupFullscreen()
    }
}

// Data class for file items
data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean
)

@Composable
fun FileManagerScreen(
    onFileSelected: (String) -> Unit
) {
    var currentPath by remember { mutableStateOf<File?>(null) }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    
    // Load files when path changes
    LaunchedEffect(currentPath) {
        files = if (currentPath == null) {
            // Show storage roots
            getStorageRoots()
        } else {
            loadFiles(currentPath!!)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A)) // Dark grey background
    ) {
        // Header with path
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A2A2A))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (currentPath == null) "Storage" else currentPath?.path ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                
                if (currentPath != null) {
                    Text(
                        text = "⬆ Up",
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clickable {
                                currentPath = currentPath?.parentFile
                                if (currentPath?.absolutePath == "/storage/emulated/0") {
                                    currentPath = null
                                }
                            }
                            .padding(start = 16.dp)
                    )
                }
            }
        }
        
        // File list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(files) { file ->
                FileListItem(
                    file = file,
                    onClick = {
                        if (file.isDirectory) {
                            currentPath = File(file.path)
                        } else {
                            // ✅ Pass file path - MainActivity will add "file://" prefix
                            onFileSelected(file.path)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun FileListItem(
    file: FileItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon indicator for folder vs file
        Text(
            text = if (file.isDirectory) "📁" else "🎬",
            fontSize = 20.sp,
            modifier = Modifier.padding(end = 12.dp)
        )
        
        // File/folder name
        Text(
            text = file.name,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

// Helper functions
fun getStorageRoots(): List<FileItem> {
    val roots = mutableListOf<FileItem>()
    
    // Internal storage
    val internalStorage = Environment.getExternalStorageDirectory()
    roots.add(FileItem(
        name = "Internal Storage",
        path = internalStorage.absolutePath,
        isDirectory = true
    ))
    
    // SD Card (if available)
    val sdCardPath = getSdCardPath()
    if (sdCardPath != null) {
        roots.add(FileItem(
            name = "SD Card",
            path = sdCardPath,
            isDirectory = true
        ))
    }
    
    return roots
}

fun loadFiles(directory: File): List<FileItem> {
    if (!directory.exists() || !directory.isDirectory) return emptyList()
    
    val videoExtensions = setOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp")
    
    return directory.listFiles()?.filter { file ->
        // Show all directories, but only video files
        file.isDirectory || videoExtensions.any { file.name.lowercase().endsWith(it) }
    }?.sortedWith(compareBy(
        { !it.isDirectory }, // Directories first
        { it.name.lowercase() } // Then alphabetically
    ))?.map { file ->
        FileItem(
            name = file.name,
            path = file.absolutePath,
            isDirectory = file.isDirectory
        )
    } ?: emptyList()
}

fun getSdCardPath(): String? {
    val storageDirs = listOf(
        "/storage/",
        "/mnt/sdcard/",
        "/mnt/extSdCard/",
        "/storage/sdcard1/",
        "/storage/extSdCard/"
    )
    
    for (path in storageDirs) {
        val file = File(path)
        if (file.exists() && file.canRead() && file.isDirectory && file != Environment.getExternalStorageDirectory()) {
            return path
        }
    }
    
    return null
}

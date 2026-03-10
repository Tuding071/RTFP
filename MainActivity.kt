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
                // STATE MANAGED IN COMPOSE
                var videoPath by remember { mutableStateOf(initialVideoPath) }
                var showFileManager by remember { mutableStateOf(initialVideoPath == null) }
                
                // BACK BUTTON HANDLER
                DisposableEffect(showFileManager) {
                    val callback = object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            if (showFileManager) {
                                finish()
                            } else {
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
                    key("file-manager") {
                        FileManagerScreen(
                            onFileSelected = { path ->
                                videoPath = "file://$path"
                                showFileManager = false
                            }
                        )
                    }
                } else {
                    key(videoPath) {
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
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
    
    // Cache file listings
    val fileCache = remember { mutableMapOf<String, List<FileItem>>() }
    
    // Load files when path changes
    LaunchedEffect(currentPath) {
        val pathKey = currentPath?.absolutePath ?: "root"
        
        files = fileCache.getOrPut(pathKey) {
            if (currentPath == null) {
                getStorageRoots()
            } else {
                loadFiles(currentPath!!)
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
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
                    text = if (currentPath == null) "Storage" else currentPath?.name ?: "",
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
                                // FIXED: Proper up navigation
                                val parent = currentPath?.parentFile
                                
                                currentPath = when {
                                    // If we're at internal storage root, go to main storage list
                                    currentPath?.absolutePath == Environment.getExternalStorageDirectory().absolutePath -> {
                                        null
                                    }
                                    // If parent is null or is root directory, go to main storage list
                                    parent == null || parent.absolutePath == "/" || parent.absolutePath == "/storage" -> {
                                        null
                                    }
                                    // Otherwise go to parent directory
                                    else -> {
                                        parent
                                    }
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
            items(files, key = { it.path }) { file ->
                FileListItem(
                    file = file,
                    onClick = {
                        if (file.isDirectory) {
                            currentPath = File(file.path)
                        } else {
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
        // Icon indicator
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
    
    // FIXED: Better SD Card detection
    val sdCardPaths = getSdCardPaths()
    sdCardPaths.forEach { path ->
        val file = File(path)
        if (file.exists() && file.canRead() && file.isDirectory) {
            roots.add(FileItem(
                name = "SD Card (${file.name})",
                path = file.absolutePath,
                isDirectory = true
            ))
        }
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

// FIXED: Comprehensive SD card detection
fun getSdCardPaths(): List<String> {
    val sdCards = mutableListOf<String>()
    
    // Method 1: Check common SD card mount points
    val possiblePaths = listOf(
        "/storage/",
        "/mnt/sdcard/",
        "/mnt/extSdCard/",
        "/storage/sdcard1/",
        "/storage/extSdCard/",
        "/storage/external_SD/",
        "/storage/UsbDriveA/",
        "/storage/UsbDriveB/",
        "/storage/emulated/0/", // Internal, but included for completeness
        "/mnt/media_rw/",
        "/storage/removable/",
        "/storage/0/"
    )
    
    // Method 2: Check all directories in /storage that aren't emulated
    try {
        val storageDir = File("/storage")
        if (storageDir.exists() && storageDir.isDirectory) {
            storageDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && 
                    dir.canRead() && 
                    !dir.name.equals("emulated", ignoreCase = true) &&
                    !dir.name.equals("self", ignoreCase = true) &&
                    dir.absolutePath != Environment.getExternalStorageDirectory().absolutePath) {
                    sdCards.add(dir.absolutePath)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    // Method 3: Check system environment for secondary storage
    try {
        val secondaryStorage = System.getenv("SECONDARY_STORAGE")
        if (!secondaryStorage.isNullOrBlank()) {
            secondaryStorage.split(":").forEach { path ->
                if (File(path).exists()) {
                    sdCards.add(path)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    // Method 4: Check external storage directories
    try {
        val externalDirs = Environment.getExternalStorageDirectory().listFiles()
        externalDirs?.forEach { dir ->
            if (dir.isDirectory && 
                dir.canRead() && 
                !dir.absolutePath.contains("emulated") &&
                dir.absolutePath != Environment.getExternalStorageDirectory().absolutePath) {
                sdCards.add(dir.absolutePath)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    // Remove duplicates and filter out invalid paths
    return sdCards.distinct().filter { path ->
        val file = File(path)
        file.exists() && file.isDirectory && file.canRead() && file.listFiles() != null
    }
}

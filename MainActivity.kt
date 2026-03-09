package com.rtfp.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

class MainActivity : ComponentActivity() {
    
    private var videoPath: String? = null
    private var showFileManager = true
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get video path from intent if any
        videoPath = extractVideoPath(intent)
        showFileManager = videoPath == null
        
        // Setup fullscreen immersive mode
        setupFullscreen()
        
        // Keep screen on while activity is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Handle back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
        
        setContent {
            MaterialTheme {
                if (showFileManager) {
                    FileManagerScreen(
                        onFileSelected = { path ->
                            videoPath = path
                            showFileManager = false
                            recreate() // Switch to player
                        },
                        onExit = { finish() }
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
        videoPath = extractVideoPath(intent)
        showFileManager = videoPath == null
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
    
    override fun onDestroy() {
        super.onDestroy()
        videoPath = null
    }
}

// Data class for file items
data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val icon: Int = android.R.drawable.ic_menu_gallery // You can add your own icons
)

@Composable
fun FileManagerScreen(
    onFileSelected: (String) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf<File?>(null) }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var showStorageOptions by remember { mutableStateOf(true) }
    
    // Load files when path changes
    LaunchedEffect(currentPath) {
        files = if (currentPath == null) {
            emptyList()
        } else {
            loadVideoFiles(currentPath!!)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A)) // Dark grey background
    ) {
        // Header
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
                    text = if (currentPath == null) "Select Storage" else currentPath?.name ?: "Files",
                    color = Color.White,
                    fontSize = 18.sp
                )
                
                Row {
                    if (currentPath != null) {
                        Text(
                            text = "⬆️ Up",
                            color = Color.White,
                            modifier = Modifier
                                .clickable {
                                    currentPath = currentPath?.parentFile
                                    if (currentPath?.absolutePath == "/storage/emulated/0") {
                                        currentPath = null
                                        showStorageOptions = true
                                    }
                                }
                                .padding(horizontal = 16.dp)
                        )
                    }
                    Text(
                        text = "✕",
                        color = Color.White,
                        modifier = Modifier
                            .clickable { onExit() }
                            .padding(horizontal = 8.dp)
                    )
                }
            }
        }
        
        if (currentPath == null) {
            // Storage Options
            StorageOptionsScreen(
                onStorageSelected = { storagePath ->
                    currentPath = File(storagePath)
                    showStorageOptions = false
                },
                onExit = onExit
            )
        } else {
            // File List
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
                                onFileSelected(file.path)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun StorageOptionsScreen(
    onStorageSelected: (String) -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Internal Storage (Phone)
        StorageOptionItem(
            name = "📱 Phone Storage",
            path = Environment.getExternalStorageDirectory().absolutePath,
            onClick = onStorageSelected
        )
        
        // SD Card (if available)
        val sdCardPath = getSdCardPath()
        if (sdCardPath != null) {
            StorageOptionItem(
                name = "💾 SD Card",
                path = sdCardPath,
                onClick = onStorageSelected
            )
        }
    }
}

@Composable
fun StorageOptionItem(
    name: String,
    path: String,
    onClick: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A2A))
            .clickable { onClick(path) }
            .padding(16.dp)
    ) {
        Text(
            text = name,
            color = Color.White,
            fontSize = 16.sp
        )
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
        // Icon
        Text(
            text = if (file.isDirectory) "📁" else "🎬",
            fontSize = 20.sp,
            modifier = Modifier.padding(end = 16.dp)
        )
        
        // File name
        Text(
            text = file.name,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

// Helper functions
fun loadVideoFiles(directory: File): List<FileItem> {
    if (!directory.exists() || !directory.isDirectory) return emptyList()
    
    val videoExtensions = setOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp")
    
    return directory.listFiles()?.filter { file ->
        file.isDirectory || videoExtensions.any { file.name.lowercase().endsWith(it) }
    }?.sortedBy { file ->
        if (file.isDirectory) 0 else 1 // Directories first
    }?.map { file ->
        FileItem(
            name = file.name,
            path = file.absolutePath,
            isDirectory = file.isDirectory
        )
    } ?: emptyList()
}

fun getSdCardPath(): String? {
    // Try to find external SD card
    val storageDirs = listOf(
        "/storage/",
        "/mnt/sdcard/",
        "/mnt/extSdCard/",
        "/storage/sdcard1/"
    )
    
    val externalStorage = System.getenv("SECONDARY_STORAGE") ?: ""
    
    // Check common paths
    val possiblePaths = externalStorage.split(":").filter { it.isNotEmpty() } + storageDirs
    
    for (path in possiblePaths) {
        val file = File(path)
        if (file.exists() && file.canRead() && file.isDirectory) {
            return path
        }
    }
    
    return null
}

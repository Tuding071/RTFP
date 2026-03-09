package com.rtfp.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
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
    
    private var videoPath: String? = null
    private var showFileManager = true
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get video path from intent if any
        videoPath = extractVideoPath(intent)
        showFileManager = videoPath == null
        
        Log.d("MainActivity", "onCreate - videoPath: $videoPath, showFileManager: $showFileManager")
        
        // Setup fullscreen immersive mode
        setupFullscreen()
        
        // Keep screen on while activity is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Handle back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showFileManager) {
                    finish()
                } else {
                    // Go back to file manager
                    showFileManager = true
                    videoPath = null
                    recreate()
                }
            }
        })
        
        setContent {
            MaterialTheme {
                if (showFileManager) {
                    FileManagerScreen(
                        onFileSelected = { path ->
                            Log.d("MainActivity", "File selected: $path")
                            videoPath = path
                            showFileManager = false
                            Log.d("MainActivity", "Switching to player - videoPath: $videoPath")
                            recreate()
                        }
                    )
                } else {
                    Log.d("MainActivity", "Showing PlayerScreen with path: $videoPath")
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
        Log.d("MainActivity", "onNewIntent - videoPath: $videoPath, showFileManager: $showFileManager")
        recreate()
    }
    
    private fun extractVideoPath(intent: Intent?): String? {
        return when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.toString()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString()
                }
            }
            else -> null
        }
    }
    
    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        // Using the new API instead of deprecated one
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            insats
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
                        text = "Up",
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clickable {
                                val parent = currentPath?.parentFile
                                currentPath = if (parent != null && parent.absolutePath != "/") {
                                    parent
                                } else {
                                    null
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
                            // Pass the file path directly
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
        // Simple indicator for folder vs file
        Text(
            text = if (file.isDirectory) "[FOLDER]" else "[FILE]",
            color = if (file.isDirectory) Color(0xFF4CAF50) else Color(0xFF2196F3),
            fontSize = 12.sp,
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
    
    // Add root directories
    val rootFile = File("/storage/")
    if (rootFile.exists() && rootFile.isDirectory) {
        rootFile.listFiles()?.forEach { file ->
            if (file.isDirectory && file.absolutePath != internalStorage.absolutePath) {
                roots.add(FileItem(
                    name = "Storage - ${file.name}",
                    path = file.absolutePath,
                    isDirectory = true
                ))
            }
        }
    }
    
    return roots
}

fun loadFiles(directory: File): List<FileItem> {
    if (!directory.exists() || !directory.isDirectory) return emptyList()
    
    val videoExtensions = setOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp", ".mpg", ".mpeg")
    
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

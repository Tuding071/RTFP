package com.rtfp.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.SharedPreferences
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
import androidx.compose.ui.platform.LocalContext
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
        
        // Setup fullscreen for player only (file manager will show bars)
        setupDisplay()
        
        // Keep screen on while activity is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContent {
            MaterialTheme {
                // STATE MANAGED IN COMPOSE
                var videoPath by remember { mutableStateOf(initialVideoPath) }
                var showFileManager by remember { mutableStateOf(initialVideoPath == null) }
                
                // Handle system bars based on screen
                LaunchedEffect(showFileManager) {
                    if (showFileManager) {
                        // Show system bars for file manager
                        WindowCompat.setDecorFitsSystemWindows(window, true)
                        val controller = WindowInsetsControllerCompat(window, window.decorView)
                        controller.show(WindowInsetsCompat.Type.systemBars())
                    } else {
                        // Hide system bars for player
                        WindowCompat.setDecorFitsSystemWindows(window, false)
                        val controller = WindowInsetsControllerCompat(window, window.decorView)
                        controller.hide(WindowInsetsCompat.Type.systemBars())
                        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
                
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
    
    private fun setupDisplay() {
        // Don't set fullscreen here - will be handled by LaunchedEffect
        WindowCompat.setDecorFitsSystemWindows(window, true)
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

// Data class for file items
data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0
)

// Sorting options
enum class SortOption {
    NAME_A_TO_Z,
    NAME_Z_TO_A,
    DATE_NEWEST,
    DATE_OLDEST,
    SIZE_LARGEST,
    SIZE_SMALLEST
}

@Composable
fun FileManagerScreen(
    onFileSelected: (String) -> Unit
) {
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf<File?>(null) }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    
    // Load saved sort option
    val prefs = remember { context.getSharedPreferences("rtfp_prefs", Context.MODE_PRIVATE) }
    var sortOption by remember {
        mutableStateOf(
            SortOption.values()[prefs.getInt("sort_option", SortOption.NAME_A_TO_Z.ordinal)]
        )
    }
    var showSortMenu by remember { mutableStateOf(false) }
    
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
        // Sort files
        files = sortFilesWithFoldersTop(files, sortOption)
    }
    
    // Re-sort when sort option changes and save preference
    LaunchedEffect(sortOption) {
        files = sortFilesWithFoldersTop(files, sortOption)
        // Save to SharedPreferences
        prefs.edit().putInt("sort_option", sortOption.ordinal).apply()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header with Home, Sort, Up buttons
        HeaderSection(
            currentPath = currentPath,
            sortOption = sortOption,
            showSortMenu = showSortMenu,
            onSortClick = { showSortMenu = true },
            onHomeClick = {
                currentPath = null
            },
            onUpClick = {
                val parent = currentPath?.parentFile
                currentPath = when {
                    parent == null -> null
                    parent.absolutePath == "/storage/emulated/0" -> null
                    parent.absolutePath == "/storage" -> null
                    parent.absolutePath == "/" -> null
                    else -> parent
                }
            }
        )
        
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
    
    // Sort menu overlay - moved outside Column to be on top
    if (showSortMenu) {
        SortMenu(
            currentSort = sortOption,
            onSortSelected = { option ->
                sortOption = option
                showSortMenu = false
            },
            onDismiss = { showSortMenu = false }
        )
    }
}

@Composable
fun HeaderSection(
    currentPath: File?,
    sortOption: SortOption,
    showSortMenu: Boolean,
    onSortClick: () -> Unit,
    onHomeClick: () -> Unit,
    onUpClick: () -> Unit
) {
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
            // Home button (left)
            Text(
                text = "Home",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .clickable { onHomeClick() }
                    .padding(end = 8.dp)
            )
            
            // Sort button (center) - make it more clickable
            Text(
                text = "Sort: ${getSortDisplayName(sortOption)} ▼",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable { onSortClick() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .background(Color(0xFF3A3A3A), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            
            // Up button (right) - only show if not at root
            if (currentPath != null) {
                Text(
                    text = "Up",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable { onUpClick() }
                        .padding(start = 8.dp)
                )
            } else {
                // Empty space for alignment when Up is hidden
                Spacer(modifier = Modifier.width(40.dp))
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

@Composable
fun SortMenu(
    currentSort: SortOption,
    onSortSelected: (SortOption) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable { onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 100.dp)
                .background(Color(0xFF2A2A2A))
                .padding(8.dp)
                .width(200.dp)
        ) {
            SortOption.values().forEach { option ->
                Text(
                    text = getSortDisplayName(option),
                    color = if (option == currentSort) Color.Yellow else Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSortSelected(option) }
                        .padding(12.dp)
                )
            }
        }
    }
}

// Helper functions
fun getSortDisplayName(option: SortOption): String = when (option) {
    SortOption.NAME_A_TO_Z -> "Name A-Z"
    SortOption.NAME_Z_TO_A -> "Name Z-A"
    SortOption.DATE_NEWEST -> "Newest first"
    SortOption.DATE_OLDEST -> "Oldest first"
    SortOption.SIZE_LARGEST -> "Largest first"
    SortOption.SIZE_SMALLEST -> "Smallest first"
}

fun sortFilesWithFoldersTop(files: List<FileItem>, option: SortOption): List<FileItem> {
    val (folders, videos) = files.partition { it.isDirectory }
    
    val sortedFolders = when (option) {
        SortOption.NAME_A_TO_Z -> folders.sortedBy { it.name.lowercase() }
        SortOption.NAME_Z_TO_A -> folders.sortedByDescending { it.name.lowercase() }
        SortOption.DATE_NEWEST -> folders.sortedByDescending { it.lastModified }
        SortOption.DATE_OLDEST -> folders.sortedBy { it.lastModified }
        SortOption.SIZE_LARGEST -> folders
        SortOption.SIZE_SMALLEST -> folders
    }
    
    val sortedVideos = when (option) {
        SortOption.NAME_A_TO_Z -> videos.sortedBy { it.name.lowercase() }
        SortOption.NAME_Z_TO_A -> videos.sortedByDescending { it.name.lowercase() }
        SortOption.DATE_NEWEST -> videos.sortedByDescending { it.lastModified }
        SortOption.DATE_OLDEST -> videos.sortedBy { it.lastModified }
        SortOption.SIZE_LARGEST -> videos.sortedByDescending { it.size }
        SortOption.SIZE_SMALLEST -> videos.sortedBy { it.size }
    }
    
    return sortedFolders + sortedVideos
}

fun getStorageRoots(): List<FileItem> {
    val roots = mutableListOf<FileItem>()
    
    // Internal storage
    val internalStorage = Environment.getExternalStorageDirectory()
    roots.add(FileItem(
        name = "Internal Storage",
        path = internalStorage.absolutePath,
        isDirectory = true,
        lastModified = internalStorage.lastModified()
    ))
    
    // SD Card
    val sdCardPath = getSdCardPath()
    if (sdCardPath != null) {
        val sdCardFile = File(sdCardPath)
        if (sdCardFile.exists() && sdCardFile.isDirectory) {
            roots.add(FileItem(
                name = "SD Card",
                path = sdCardPath,
                isDirectory = true,
                lastModified = sdCardFile.lastModified()
            ))
        }
    }
    
    return roots
}

fun loadFiles(directory: File): List<FileItem> {
    if (!directory.exists() || !directory.isDirectory) return emptyList()
    
    val videoExtensions = setOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp")
    
    return directory.listFiles()?.filter { file ->
        file.isDirectory || videoExtensions.any { file.name.lowercase().endsWith(it) }
    }?.sortedWith(compareBy(
        { !it.isDirectory },
        { it.name.lowercase() }
    ))?.map { file ->
        FileItem(
            name = file.name,
            path = file.absolutePath,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0,
            lastModified = file.lastModified()
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
        try {
            val file = File(path)
            if (file.exists() && file.canRead() && file.isDirectory && 
                file.absolutePath != Environment.getExternalStorageDirectory().absolutePath) {
                return path
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    try {
        val storageDir = File("/storage")
        if (storageDir.exists() && storageDir.isDirectory) {
            storageDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && 
                    dir.canRead() && 
                    dir.absolutePath != Environment.getExternalStorageDirectory().absolutePath &&
                    !dir.name.equals("emulated", ignoreCase = true) &&
                    !dir.name.equals("self", ignoreCase = true)) {
                    return dir.absolutePath
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    return null
}

package com.rtfp.player

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get initial video path from intent (if opened from file manager)
        val initialVideoPath = extractVideoPath(intent)
        
        // Setup normal fullscreen (keep status and nav bars)
        setupDisplay()
        
        // Keep screen on while activity is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContent {
            MaterialTheme {
                // STATE MANAGED IN COMPOSE
                var videoPath by remember { mutableStateOf(initialVideoPath) }
                var showFileManager by remember { mutableStateOf(initialVideoPath == null) }
                
                // BACK BUTTON HANDLER - Now works as Up navigation
                DisposableEffect(showFileManager) {
                    val callback = object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            if (showFileManager) {
                                // Let the FileManagerScreen handle its own back navigation
                                // We'll pass this through a callback
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
                            },
                            onBackPressed = {
                                // If FileManagerScreen can't handle back, finish activity
                                finish()
                            }
                        )
                    }
                } else {
                    key(videoPath) {
                        PlayerScreen(
                            videoPath = videoPath,
                            onVideoLoaded = { width, height ->
                                setOrientationForVideo(width, height)
                            },
                            onBackToFiles = {
                                showFileManager = true
                                videoPath = null
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
        // Don't hide system bars - keep status and nav bars visible
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
        // Keep screen on but don't hide system bars
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

// Data class for file items with metadata
data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    var thumbnailPath: String? = null
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
    onFileSelected: (String) -> Unit,
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf<File?>(null) }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var sortOption by remember { mutableStateOf(SortOption.NAME_A_TO_Z) }
    var showSortMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // Cache for thumbnails
    val thumbnailCache = remember { mutableMapOf<String, String>() }
    val thumbnailGenerator = remember { ThumbnailGenerator(context) }
    
    // Load files when path changes
    LaunchedEffect(currentPath) {
        files = if (currentPath == null) {
            getStorageRoots()
        } else {
            loadFiles(currentPath!!)
        }
        // Sort files
        files = sortFiles(files, sortOption)
        // Generate thumbnails for video files
        generateThumbnails(files, thumbnailGenerator, thumbnailCache)
    }
    
    // Re-sort when sort option changes
    LaunchedEffect(sortOption) {
        files = sortFiles(files, sortOption)
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .systemBarsPadding() // Add padding for system bars
    ) {
        // Header with path and controls
        HeaderSection(
            currentPath = currentPath,
            files = files,
            sortOption = sortOption,
            showSortMenu = showSortMenu,
            onSortClick = { showSortMenu = !showSortMenu },
            onSortOptionSelected = { sortOption = it },
            onUpClick = {
                // Handle up navigation
                val parent = currentPath?.parentFile
                currentPath = when {
                    parent == null -> null
                    parent.absolutePath == "/storage/emulated/0" -> null
                    parent.absolutePath == "/storage" -> null
                    parent.absolutePath == "/" -> null
                    else -> parent
                }
            },
            onBackPressed = onBackPressed
        )
        
        // File list with count
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Show file count at top
                item {
                    FileCountHeader(count = files.size)
                }
                
                items(files, key = { it.path }) { file ->
                    FileListItem(
                        file = file,
                        thumbnailCache = thumbnailCache,
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
            
            // Sort menu overlay
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
    }
}

@Composable
fun HeaderSection(
    currentPath: File?,
    files: List<FileItem>,
    sortOption: SortOption,
    showSortMenu: Boolean,
    onSortClick: () -> Unit,
    onSortOptionSelected: (SortOption) -> Unit,
    onUpClick: () -> Unit,
    onBackPressed: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A2A))
    ) {
        // Top row with path and controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Path and navigation
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Back/Up button
                Text(
                    text = if (currentPath == null) "← Back" else "⬆ Up",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable {
                            if (currentPath == null) {
                                onBackPressed()
                            } else {
                                onUpClick()
                            }
                        }
                        .padding(end = 16.dp)
                )
                
                // Current path
                Text(
                    text = if (currentPath == null) "Storage" else currentPath?.name ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
            
            // Sort button
            Text(
                text = "Sort: ${getSortDisplayName(sortOption)} ▼",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable { onSortClick() }
                    .padding(start = 8.dp)
            )
        }
    }
}

@Composable
fun FileCountHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF333333))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "📁 $count item${if (count != 1) "s" else ""}",
            color = Color.LightGray,
            fontSize = 12.sp
        )
    }
}

@Composable
fun FileListItem(
    file: FileItem,
    thumbnailCache: Map<String, String>,
    onClick: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(if (isPressed) Color(0xFF3A3A3A) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail or icon
        Box(
            modifier = Modifier
                .size(50.dp)
                .padding(end = 12.dp)
        ) {
            if (!file.isDirectory && thumbnailCache.containsKey(file.path)) {
                // Show thumbnail
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(File(thumbnailCache[file.path]!!))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Show emoji icon
                Text(
                    text = if (file.isDirectory) "📁" else "🎬",
                    fontSize = 24.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        
        // File info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = file.name,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1
            )
            
            // File details
            Row {
                if (!file.isDirectory) {
                    Text(
                        text = formatFileSize(file.size),
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(
                    text = formatDate(file.lastModified),
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }
        
        // Long press preview (simplified - would need ExoPlayer for actual preview)
        if (isPressed && !file.isDirectory) {
            // Show preview indicator
            Text(
                text = "▶",
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
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
                .align(Alignment.TopEnd)
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
fun getSortDisplayName(option: SortOption): String {
    return when (option) {
        SortOption.NAME_A_TO_Z -> "Name A-Z"
        SortOption.NAME_Z_TO_A -> "Name Z-A"
        SortOption.DATE_NEWEST -> "Newest first"
        SortOption.DATE_OLDEST -> "Oldest first"
        SortOption.SIZE_LARGEST -> "Largest first"
        SortOption.SIZE_SMALLEST -> "Smallest first"
    }
}

fun sortFiles(files: List<FileItem>, option: SortOption): List<FileItem> {
    return when (option) {
        SortOption.NAME_A_TO_Z -> files.sortedBy { it.name.lowercase() }
        SortOption.NAME_Z_TO_A -> files.sortedByDescending { it.name.lowercase() }
        SortOption.DATE_NEWEST -> files.sortedByDescending { it.lastModified }
        SortOption.DATE_OLDEST -> files.sortedBy { it.lastModified }
        SortOption.SIZE_LARGEST -> files.sortedByDescending { if (it.isDirectory) 0 else it.size }
        SortOption.SIZE_SMALLEST -> files.sortedBy { if (it.isDirectory) 0 else it.size }
    }
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun formatDate(timestamp: Long): String {
    if (timestamp == 0L) return "Unknown"
    val date = Date(timestamp)
    val format = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return format.format(date)
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
    
    // SD Card detection
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
    
    return directory.listFiles()?.mapNotNull { file ->
        if (file.isDirectory || videoExtensions.any { file.name.lowercase().endsWith(it) }) {
            FileItem(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0,
                lastModified = file.lastModified()
            )
        } else null
    } ?: emptyList()
}

fun getSdCardPath(): String? {
    val storageDirs = listOf(
        "/storage/",
        "/mnt/sdcard/",
        "/mnt/extSdCard/",
        "/storage/sdcard1/",
        "/storage/extSdCard/",
        "/storage/emulated/0/",
        "/storage/UsbDriveA/",
        "/storage/UsbDriveB/",
        "/storage/external_SD/",
        "/mnt/media_rw/"
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

// Thumbnail Generator Class
class ThumbnailGenerator(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val thumbnailDir = File(context.cacheDir, "thumbnails")
    
    init {
        if (!thumbnailDir.exists()) {
            thumbnailDir.mkdirs()
        }
    }
    
    fun generateThumbnail(videoPath: String, callback: (String?) -> Unit) {
        executor.execute {
            try {
                val videoFile = File(videoPath)
                if (!videoFile.exists()) {
                    callback(null)
                    return@execute
                }
                
                val thumbnailFile = File(thumbnailDir, "${videoFile.nameWithoutExtension}.jpg")
                if (thumbnailFile.exists()) {
                    callback(thumbnailFile.absolutePath)
                    return@execute
                }
                
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(videoPath)
                
                // Get frame at 1 second
                val bitmap = retriever.getFrameAtTime(1000000) // 1 second in microseconds
                
                if (bitmap != null) {
                    // Scale bitmap to thumbnail size
                    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 150, 150, true)
                    
                    // Save to cache
                    FileOutputStream(thumbnailFile).use { out ->
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    }
                    
                    scaledBitmap.recycle()
                    bitmap.recycle()
                    
                    callback(thumbnailFile.absolutePath)
                } else {
                    callback(null)
                }
                
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
                callback(null)
            }
        }
    }
    
    fun cleanup() {
        // Delete old thumbnails (older than 7 days)
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
        thumbnailDir.listFiles()?.forEach { file ->
            if (file.lastModified() < sevenDaysAgo) {
                file.delete()
            }
        }
    }
}

// Function to generate thumbnails for video files
fun generateThumbnails(
    files: List<FileItem>,
    generator: ThumbnailGenerator,
    cache: MutableMap<String, String>
) {
    files.filter { !it.isDirectory }.forEach { file ->
        if (!cache.containsKey(file.path)) {
            generator.generateThumbnail(file.path) { thumbnailPath ->
                if (thumbnailPath != null) {
                    cache[file.path] = thumbnailPath
                }
            }
        }
    }
}

package com.rtfp.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get initial video path from intent
        val initialVideoPath = extractVideoPath(intent)
        
        // Setup normal display (show status and nav bars)
        setupDisplay()
        
        // Keep screen on while activity is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContent {
            MaterialTheme {
                var videoPath by remember { mutableStateOf(initialVideoPath) }
                var showFileManager by remember { mutableStateOf(initialVideoPath == null) }
                
                // Simple back handler - just exits
                DisposableEffect(Unit) {
                    val callback = object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            finish()
                        }
                    }
                    onBackPressedDispatcher.addCallback(callback)
                    onDispose { callback.remove() }
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
        // Show system bars
        WindowCompat.setDecorFitsSystemWindows(window, true)
        
        // Make status bar icons light for dark background
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
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
    var sortOption by remember { mutableStateOf(SortOption.NAME_A_TO_Z) }
    var showSortMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var isScrolling by remember { mutableStateOf(false) }
    
    // Thumbnail manager
    val thumbnailManager = remember { ThumbnailManager(context) }
    val thumbnails = thumbnailManager.thumbnails.collectAsState()
    
    // Load files when path changes
    LaunchedEffect(currentPath) {
        files = if (currentPath == null) {
            getStorageRoots()
        } else {
            loadFiles(currentPath!!)
        }
        // Sort with folders on top
        files = sortFilesWithFoldersTop(files, sortOption)
        // Generate thumbnails
        thumbnailManager.generateForFiles(files)
    }
    
    // Re-sort when sort option changes
    LaunchedEffect(sortOption) {
        files = sortFilesWithFoldersTop(files, sortOption)
    }
    
    // Detect scrolling
    LaunchedEffect(listState.isScrollInProgress) {
        isScrolling = listState.isScrollInProgress
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .statusBarsPadding() // Add padding for status bar
            .navigationBarsPadding() // Add padding for nav bar
    ) {
        // Header with path and sort button
        HeaderSection(
            currentPath = currentPath,
            sortOption = sortOption,
            showSortMenu = showSortMenu,
            onSortClick = { showSortMenu = !showSortMenu },
            onSortOptionSelected = { 
                sortOption = it
                showSortMenu = false
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
        
        // List resize handle (1-20 items visible)
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(files, key = { it.path }) { file ->
                    FileListItem(
                        file = file,
                        thumbnails = if (!isScrolling) thumbnails.value[file.path] else null,
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
    sortOption: SortOption,
    showSortMenu: Boolean,
    onSortClick: () -> Unit,
    onSortOptionSelected: (SortOption) -> Unit,
    onUpClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A2A))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Path and Up button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (currentPath != null) {
                Text(
                    text = "⬆ Up",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable { onUpClick() }
                        .padding(end = 16.dp)
                )
            }
            
            Text(
                text = if (currentPath == null) "Storage" else currentPath.name ?: "",
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

@Composable
fun FileListItem(
    file: FileItem,
    thumbnails: List<Bitmap>?,
    onClick: () -> Unit
) {
    var currentFrame by remember { mutableStateOf(0) }
    
    // Handle thumbnail slideshow
    LaunchedEffect(thumbnails) {
        if (thumbnails != null && thumbnails.size > 1) {
            while (true) {
                delay(500) // 500ms per frame
                currentFrame = (currentFrame + 1) % thumbnails.size
            }
        }
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail or icon
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF333333))
        ) {
            if (thumbnails != null && thumbnails.isNotEmpty()) {
                // Show current frame of slideshow
                androidx.compose.foundation.Image(
                    bitmap = thumbnails[currentFrame].asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = if (file.isDirectory) "📁" else "🎬",
                    fontSize = 24.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // File info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1
            )
            
            Text(
                text = if (file.isDirectory) "Folder" else formatFileSize(file.size),
                color = Color.Gray,
                fontSize = 12.sp
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

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
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

// Thumbnail Manager Class
class ThumbnailManager(private val context: Context) {
    private val thumbnailDir = File(context.cacheDir, "video_thumbnails")
    private val executor = Executors.newSingleThreadExecutor()
    private val _thumbnails = MutableStateFlow<Map<String, List<Bitmap>>>(emptyMap())
    val thumbnails: MutableStateFlow<Map<String, List<Bitmap>>> = _thumbnails
    
    // Limit to 200 videos
    private val maxVideos = 200
    private val videoList = mutableListOf<String>()
    
    init {
        if (!thumbnailDir.exists()) {
            thumbnailDir.mkdirs()
        }
        cleanupOldThumbnails()
    }
    
    fun generateForFiles(files: List<FileItem>) {
        val videoFiles = files.filter { !it.isDirectory }
        
        // Update video list and enforce limit
        videoList.addAll(videoFiles.map { it.path })
        while (videoList.size > maxVideos) {
            val oldest = videoList.removeAt(0)
            deleteThumbnails(oldest)
        }
        
        // Generate thumbnails for new videos
        videoFiles.forEach { file ->
            if (!_thumbnails.value.containsKey(file.path)) {
                generateThumbnails(file.path)
            }
        }
    }
    
    private fun generateThumbnails(videoPath: String) {
        executor.execute {
            try {
                val videoFile = File(videoPath)
                if (!videoFile.exists()) return@execute
                
                // Check if already cached
                val cacheKey = videoFile.nameWithoutExtension
                val cachedFile = File(thumbnailDir, "${cacheKey}_frames.dat")
                
                val frames = if (cachedFile.exists()) {
                    // Load from cache
                    loadFramesFromCache(cachedFile)
                } else {
                    // Generate new frames
                    generateFrames(videoPath).also { frames ->
                        // Cache the frames
                        saveFramesToCache(cachedFile, frames)
                    }
                }
                
                if (frames.isNotEmpty()) {
                    _thumbnails.value = _thumbnails.value + (videoPath to frames)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun generateFrames(videoPath: String): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()
        val retriever = MediaMetadataRetriever()
        
        try {
            retriever.setDataSource(videoPath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
            
            if (duration > 0) {
                // Get 10 frames at different positions
                for (i in 0 until 10) {
                    val timeUs = (duration * 1000 * i / 9) // 0 to 100% in 9 steps
                    val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null) {
                        // Scale to thumbnail size
                        val scaled = Bitmap.createScaledBitmap(bitmap, 120, 120, true)
                        frames.add(scaled)
                        bitmap.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            retriever.release()
        }
        
        return frames
    }
    
    private fun saveFramesToCache(file: File, frames: List<Bitmap>) {
        try {
            FileOutputStream(file).use { out ->
                // Write number of frames
                out.write(frames.size)
                
                frames.forEach { bitmap ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun loadFramesFromCache(file: File): List<Bitmap> {
        // Simplified - in real implementation would read and decode
        return emptyList()
    }
    
    private fun deleteThumbnails(videoPath: String) {
        _thumbnails.value = _thumbnails.value - videoPath
        // Also delete cached files
        val videoFile = File(videoPath)
        val cacheFile = File(thumbnailDir, "${videoFile.nameWithoutExtension}_frames.dat")
        cacheFile.delete()
    }
    
    private fun cleanupOldThumbnails() {
        // Delete thumbnails older than 7 days
        val weekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
        thumbnailDir.listFiles()?.forEach { file ->
            if (file.lastModified() < weekAgo) {
                file.delete()
            }
        }
    }
}

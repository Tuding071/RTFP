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
import android.widget.MediaController
import android.widget.VideoView
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
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
        
        // Setup display - show bars in file manager, hide in player
        setupDisplay()
        
        // Keep screen on while activity is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContent {
            MaterialTheme {
                var videoPath by remember { mutableStateOf(initialVideoPath) }
                var showFileManager by remember { mutableStateOf(initialVideoPath == null) }
                
                // Back handler
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
        // Show system bars by default
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
    var sortOption by remember { mutableStateOf(SortOption.NAME_A_TO_Z) }
    var showSortMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    
    // Preview state
    var previewVideo by remember { mutableStateOf<String?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    
    // Thumbnail manager - using simple mutableStateMap
    val thumbnails = remember { mutableStateMapOf<String, Bitmap>() }
    val thumbnailManager = remember { ThumbnailManager(context, thumbnails) }
    
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
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .statusBarsPadding()
            .navigationBarsPadding()
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
        
        // File list
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(files, key = { it.path }) { file ->
                    FileListItem(
                        file = file,
                        thumbnail = thumbnails[file.path],
                        onPreviewClick = {
                            if (!file.isDirectory) {
                                previewVideo = file.path
                                showPreview = true
                            }
                        },
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
            
            // Video preview popup with native VideoView
            if (showPreview && previewVideo != null) {
                VideoPreviewPopup(
                    videoPath = previewVideo!!,
                    onClose = {
                        showPreview = false
                        previewVideo = null
                    }
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
    thumbnail: Bitmap?,
    onPreviewClick: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF333333))
        ) {
            if (thumbnail != null) {
                androidx.compose.foundation.Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else if (!file.isDirectory) {
                // Placeholder for video without thumbnail
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF444444))
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
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
            
            Text(
                text = if (file.isDirectory) "Folder" else formatFileSize(file.size),
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
        
        // Preview button for videos
        if (!file.isDirectory) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Preview",
                color = Color(0xFF4CAF50),
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF2A2A2A))
                    .clickable { onPreviewClick() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun VideoPreviewPopup(
    videoPath: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val videoUri = Uri.parse(videoPath)
    
    // Create VideoView with MediaController
    val videoView = remember {
        VideoView(context).apply {
            setVideoURI(videoUri)
            
            // Create and set MediaController
            val mediaController = MediaController(context)
            mediaController.setAnchorView(this)
            setMediaController(mediaController)
            
            setOnPreparedListener { mp ->
                mp.isLooping = true
                start()
            }
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            videoView.stopPlayback()
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable { onClose() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.6f)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header with close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A2A2A))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "✕ Close",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { onClose() }
                            .padding(4.dp)
                    )
                    
                    Text(
                        text = File(videoPath).name,
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                }
                
                // Native VideoView
                AndroidView(
                    factory = { videoView },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
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

// Thumbnail Manager Class - simplified with mutableStateMap
class ThumbnailManager(
    private val context: Context,
    private val thumbnails: MutableMap<String, Bitmap>
) {
    private val thumbnailDir = File(context.cacheDir, "thumbnails")
    private val maxVideos = 1000
    private val videoQueue = mutableListOf<String>()
    
    init {
        if (!thumbnailDir.exists()) {
            thumbnailDir.mkdirs()
        }
    }
    
    fun generateForFiles(files: List<FileItem>) {
        val videoFiles = files.filter { !it.isDirectory }
        
        videoFiles.forEach { file ->
            if (!thumbnails.containsKey(file.path)) {
                generateThumbnail(file.path)
            }
        }
    }
    
    private fun generateThumbnail(videoPath: String) {
        // Run in background
        Thread {
            try {
                // Check cache first
                val videoFile = File(videoPath)
                val cacheFile = File(thumbnailDir, "${videoFile.nameWithoutExtension}.jpg")
                
                val bitmap = if (cacheFile.exists()) {
                    BitmapFactory.decodeFile(cacheFile.absolutePath)
                } else {
                    // Generate new thumbnail
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(videoPath)
                    val frame = retriever.getFrameAtTime(1000000) // 1 second
                    retriever.release()
                    
                    if (frame != null) {
                        // Scale to thumbnail size
                        val scaled = Bitmap.createScaledBitmap(frame, 120, 120, true)
                        
                        // Save to cache
                        FileOutputStream(cacheFile).use { out ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        
                        // Manage cache size
                        manageCache(videoPath)
                        
                        scaled
                    } else {
                        null
                    }
                }
                
                if (bitmap != null) {
                    // Update on main thread
                    (context as? MainActivity)?.runOnUiThread {
                        thumbnails[videoPath] = bitmap
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
    
    private fun manageCache(videoPath: String) {
        videoQueue.add(videoPath)
        
        // Remove oldest if over limit
        while (videoQueue.size > maxVideos) {
            val oldest = videoQueue.removeAt(0)
            thumbnails.remove(oldest)
            
            // Delete cached file
            val videoFile = File(oldest)
            val cacheFile = File(thumbnailDir, "${videoFile.nameWithoutExtension}.jpg")
            cacheFile.delete()
        }
    }
}

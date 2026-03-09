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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.util.UUID

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
                    TabbedFileManagerScreen(
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

// Tab data class
data class FileManagerTab(
    val id: String = UUID.randomUUID().toString(),
    var currentPath: File? = null,
    var history: MutableList<File> = mutableListOf(),
    var isStorageView: Boolean = true
) {
    fun navigateTo(path: File?) {
        if (path == null) {
            currentPath = null
            isStorageView = true
            history.clear()
        } else {
            if (currentPath != null) {
                history.add(currentPath!!)
            }
            currentPath = path
            isStorageView = false
        }
    }
    
    fun navigateBack(): Boolean {
        return if (history.isNotEmpty()) {
            currentPath = history.removeAt(history.size - 1)
            isStorageView = false
            true
        } else {
            currentPath = null
            isStorageView = true
            false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabbedFileManagerScreen(
    onFileSelected: (String) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    var tabs by remember { mutableStateOf(listOf(FileManagerTab())) }
    var selectedTabIndex by remember { mutableStateOf(0) }
    var showNewTabDialog by remember { mutableStateOf(false) }
    var longPressedPath by remember { mutableStateOf<File?>(null) }
    var showTabMenu by remember { mutableStateOf(false) }
    var tabMenuPosition by remember { mutableStateOf(0) }
    val tabListState = rememberLazyListState()
    
    // Load files for current tab
    val currentTab = tabs.getOrNull(selectedTabIndex)
    var files by remember(currentTab?.currentPath, selectedTabIndex) {
        mutableStateOf(if (currentTab?.isStorageView == true) emptyList() else loadVideoFiles(currentTab?.currentPath))
    }
    
    // Update files when tab changes or path changes
    LaunchedEffect(currentTab?.currentPath, selectedTabIndex) {
        files = if (currentTab?.isStorageView == true) {
            emptyList()
        } else {
            loadVideoFiles(currentTab?.currentPath)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
    ) {
        // Tab Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A2A2A))
        ) {
            LazyRow(
                state = tabListState,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                contentPadding = PaddingValues(end = 48.dp)
            ) {
                items(tabs.size) { index ->
                    val tab = tabs[index]
                    TabItem(
                        tab = tab,
                        isSelected = index == selectedTabIndex,
                        onSelect = { selectedTabIndex = index },
                        onClose = {
                            if (tabs.size > 1) {
                                tabs = tabs.toMutableList().apply { removeAt(index) }
                                if (selectedTabIndex >= tabs.size) {
                                    selectedTabIndex = tabs.size - 1
                                }
                            }
                        },
                        onLongPress = {
                            longPressedPath = tab.currentPath
                            showNewTabDialog = true
                        }
                    )
                }
            }
            
            // New Tab Button
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .background(Color(0xFF2A2A2A))
                    .clickable {
                        tabs = tabs + FileManagerTab()
                        selectedTabIndex = tabs.size - 1
                        // Scroll to new tab
                        LaunchedEffect(Unit) {
                            tabListState.animateScrollToItem(tabs.size - 1)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("+", color = Color.White, fontSize = 20.sp)
            }
        }
        
        // Navigation Bar
        if (currentTab != null) {
            NavigationBar(
                currentPath = currentTab.currentPath,
                onBack = {
                    currentTab.navigateBack()
                    // Force recompose
                    tabs = tabs.toList()
                },
                onExit = onExit
            )
        }
        
        // Content
        if (currentTab != null) {
            if (currentTab.isStorageView) {
                // Storage Options
                StorageOptionsScreen(
                    onStorageSelected = { storagePath ->
                        currentTab.navigateTo(File(storagePath))
                        // Force recompose
                        tabs = tabs.toList()
                    }
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
                                    currentTab.navigateTo(File(file.path))
                                    // Force recompose
                                    tabs = tabs.toList()
                                } else {
                                    onFileSelected(file.path)
                                }
                            },
                            onLongPress = {
                                longPressedPath = File(file.path)
                                showNewTabDialog = true
                            }
                        )
                    }
                }
            }
        }
    }
    
    // New Tab Dialog
    if (showNewTabDialog && longPressedPath != null) {
        AlertDialog(
            onDismissRequest = {
                showNewTabDialog = false
                longPressedPath = null
            },
            title = {
                Text("Open in New Tab", color = Color.White)
            },
            text = {
                Text(
                    text = "Open ${longPressedPath?.name} in a new tab?",
                    color = Color.White.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        longPressedPath?.let { path ->
                            val newTab = FileManagerTab()
                            if (path.isDirectory) {
                                newTab.navigateTo(path)
                            } else {
                                // If it's a file, open its parent directory
                                newTab.navigateTo(path.parentFile)
                            }
                            tabs = tabs + newTab
                            selectedTabIndex = tabs.size - 1
                        }
                        showNewTabDialog = false
                        longPressedPath = null
                    }
                ) {
                    Text("Open", color = Color(0xFF4CAF50))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNewTabDialog = false
                        longPressedPath = null
                    }
                ) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF2A2A2A)
        )
    }
}

@Composable
fun TabItem(
    tab: FileManagerTab,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onLongPress: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(if (isSelected) Color(0xFF3A3A3A) else Color(0xFF2A2A2A))
            .clickable { onSelect() }
            .then(
                Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { onLongPress() }
                    )
                }
            )
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = tab.currentPath?.name ?: "Storage",
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 120.dp)
            )
            
            // Close button (X)
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onClose() }
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✕",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun NavigationBar(
    currentPath: File?,
    onBack: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A2A))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Up/Back button
                if (currentPath != null) {
                    Text(
                        text = "⬆️ Up",
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clickable { onBack() }
                            .padding(end = 8.dp)
                    )
                }
                
                // Current path
                Text(
                    text = currentPath?.absolutePath ?: "Storage",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
            
            // Exit button
            Text(
                text = "✕",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable { onExit() }
                    .padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
fun StorageOptionsScreen(
    onStorageSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Select Storage",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
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
            .clip(MaterialTheme.shapes.medium)
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
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(
                Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { onLongPress() }
                    )
                }
            )
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

// Data class for file items
data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean
)

// Helper functions
fun loadVideoFiles(directory: File?): List<FileItem> {
    if (directory == null || !directory.exists() || !directory.isDirectory) return emptyList()
    
    val videoExtensions = setOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp")
    
    return directory.listFiles()?.filter { file ->
        file.isDirectory || videoExtensions.any { file.name.lowercase().endsWith(it) }
    }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        ?.map { file ->
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
        "/storage/emulated/0/",
        "/mnt/sdcard/",
        "/mnt/extSdCard/",
        "/storage/sdcard1/",
        "/storage/extSdCard/",
        "/storage/usbcard1/"
    )
    
    val externalStorage = System.getenv("SECONDARY_STORAGE") ?: ""
    
    // Check common paths
    val possiblePaths = externalStorage.split(":").filter { it.isNotEmpty() } + storageDirs
    
    for (path in possiblePaths) {
        val file = File(path)
        if (file.exists() && file.canRead() && file.isDirectory && file.listFiles()?.isNotEmpty() == true) {
            return path
        }
    }
    
    return null
}

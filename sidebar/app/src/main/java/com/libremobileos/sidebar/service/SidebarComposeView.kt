package com.libremobileos.sidebar.service

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.room.SmartClipboardEntity
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarComposeView(
    viewModel: ServiceViewModel,
    opensFromLeft: Boolean,
    launchApp: (AppInfo) -> Unit,
    closeSidebar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sidebarAppList by viewModel.sidebarAppListFlow.collectAsState()
    val smartClipboardItems by viewModel.smartClipboardItemsFlow.collectAsState()
    val smartClipboardEnabled by viewModel.smartClipboardEnabledFlow.collectAsState()

    val pagerState = rememberPagerState(pageCount = { if (smartClipboardEnabled) 2 else 1 })
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val hostView = LocalView.current

    val minSidebarWidth = 130.dp
    val minSidebarHeight = 168.dp

    val savedGeometry = remember { viewModel.getSidebarGeometry() }
    var sidebarWidth by remember { mutableStateOf(savedGeometry.first.dp) }
    var sidebarHeight by remember { mutableStateOf(savedGeometry.second.dp) }
    var verticalOffset by remember { mutableStateOf(savedGeometry.third) }

    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    var showClearClipboardDialog by remember { mutableStateOf(false) }

    val maxScreenWidth = config.screenWidthDp.dp * 0.85f
    val panelAlignment = if (opensFromLeft) Alignment.CenterStart else Alignment.CenterEnd
    val panelShape = if (opensFromLeft) {
        RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 12.dp, bottomEnd = 12.dp)
    } else {
        RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 0.dp, bottomEnd = 0.dp)
    }
    val handleAlignment = if (opensFromLeft) Alignment.TopEnd else Alignment.TopStart
    val resizeHandleAlignment = if (opensFromLeft) Alignment.BottomEnd else Alignment.BottomStart
    val contentPadding = if (opensFromLeft) {
        PaddingValues(start = 6.dp, end = 4.dp)
    } else {
        PaddingValues(start = 4.dp, end = 6.dp)
    }
    val headerPadding = if (opensFromLeft) {
        PaddingValues(top = 16.dp, bottom = 4.dp, start = 0.dp, end = 38.dp)
    } else {
        PaddingValues(top = 16.dp, bottom = 4.dp, start = 38.dp, end = 0.dp)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier)
            .pointerInput(closeSidebar) {
                detectTapGestures { tapOffset ->
                    if (!cardBounds.contains(tapOffset)) closeSidebar()
                }
            }
    ) {

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        contentAlignment = panelAlignment
    ) {
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val maxSidebarHeight = maxHeight * 0.95f

        val safeSidebarHeight = sidebarHeight.coerceIn(minSidebarHeight, maxSidebarHeight)
        val safeSidebarWidth = sidebarWidth.coerceIn(minSidebarWidth, maxScreenWidth)
        val limit = ((containerHeightPx - with(density) { safeSidebarHeight.toPx() }) / 2f).coerceAtLeast(0f)
        val safeOffset = verticalOffset.coerceIn(-limit, limit)

        LaunchedEffect(safeSidebarHeight, safeSidebarWidth, safeOffset) {
            var changed = false
            if (sidebarHeight != safeSidebarHeight) { sidebarHeight = safeSidebarHeight; changed = true }
            if (sidebarWidth != safeSidebarWidth) { sidebarWidth = safeSidebarWidth; changed = true }
            if (verticalOffset != safeOffset) { verticalOffset = safeOffset; changed = true }
            if (changed) {
                viewModel.saveSidebarGeometry(safeSidebarWidth.value, safeSidebarHeight.value, safeOffset)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = panelShape,
            modifier = Modifier
                .offset { IntOffset(0, safeOffset.roundToInt()) }
                .width(safeSidebarWidth)
                .height(safeSidebarHeight)
                .onGloballyPositioned { cardBounds = it.boundsInRoot() }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(headerPadding),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (pagerState.currentPage == 0) Icons.Default.Apps else Icons.Default.ContentPaste,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable {
                                    if (pagerState.currentPage == 0) {
                                        launchApp(viewModel.allAppActivity)
                                    } else if (smartClipboardItems.isNotEmpty()) {
                                        showClearClipboardDialog = true
                                    }
                                }
                        )
                        IconButton(
                            onClick = { viewModel.openSidebarSettings(); closeSidebar() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Settings, null, Modifier.size(22.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) { page ->
                        if (page == 0) {
                            AppGridContent(sidebarAppList, launchApp)
                        } else {
                            ClipboardListContent(smartClipboardItems, viewModel, hostView)
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(pagerState.pageCount) { iteration ->
                            val isSelected = pagerState.currentPage == iteration
                            Box(modifier = Modifier.size(if (isSelected) 6.dp else 4.dp).clip(CircleShape).background(if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)))
                            if (iteration < pagerState.pageCount - 1) Spacer(Modifier.width(6.dp))
                        }
                    }
                }


                Box(modifier = Modifier.align(handleAlignment).size(width = 40.dp, height = 56.dp).pointerInput(containerHeightPx) {
                    detectDragGestures(
                        onDragEnd = { viewModel.saveSidebarGeometry(sidebarWidth.value, sidebarHeight.value, verticalOffset) }
                    ) { change, dragAmount ->
                        change.consume()
                        val currentLimit = (containerHeightPx - with(density) { sidebarHeight.toPx() }) / 2
                        verticalOffset = (verticalOffset + dragAmount.y).coerceIn(-currentLimit, currentLimit)
                    }
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.OpenWith, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                }

                Box(modifier = Modifier.align(resizeHandleAlignment).size(width = 40.dp, height = 56.dp).pointerInput(containerHeightPx) {
                    detectDragGestures(
                        onDragEnd = { viewModel.saveSidebarGeometry(sidebarWidth.value, sidebarHeight.value, verticalOffset) }
                    ) { change, dragAmount ->
                        change.consume()
                        with(density) {
                            val widthDelta = if (opensFromLeft) dragAmount.x.toDp() else -dragAmount.x.toDp()
                            sidebarWidth = (sidebarWidth + widthDelta).coerceIn(minSidebarWidth, maxScreenWidth)
                            val newHeight = (sidebarHeight + (dragAmount.y.toDp() * 2)).coerceIn(minSidebarHeight, maxSidebarHeight)
                            sidebarHeight = newHeight

                            val currentLimit = (containerHeightPx - newHeight.toPx()) / 2
                            verticalOffset = verticalOffset.coerceIn(-currentLimit, currentLimit)
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.edit_24px), null, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                }
            }
        }
    }

    if (showClearClipboardDialog) {
        AlertDialog(
            onDismissRequest = { showClearClipboardDialog = false },
            title = { Text(stringResource(R.string.smart_clipboard_clear_title)) },
            text = { Text(stringResource(R.string.smart_clipboard_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearUnpinnedSmartClipboard()
                    showClearClipboardDialog = false
                }) {
                    Text(stringResource(R.string.smart_clipboard_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearClipboardDialog = false }) {
                    Text(stringResource(R.string.smart_clipboard_cancel))
                }
            }
        )
    }
    }
}

@Composable
private fun AppGridContent(appList: List<AppInfo>, onLaunch: (AppInfo) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight

        if (appList.isEmpty()) return@BoxWithConstraints

        if (appList.size == 1) {
            AppIconItem(appList[0], onLaunch, availableWidth)
        } else {
            val cols = if (availableWidth > 200.dp) 3 else 2
            val iconSize = 44.dp

            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .heightIn(max = availableHeight)
                    .wrapContentHeight(Alignment.CenterVertically)
            ) {
                items(appList) { appInfo ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Image(
                            painter = rememberDrawablePainter(drawable = appInfo.icon),
                            contentDescription = null,
                            modifier = Modifier.size(iconSize).clip(CircleShape).clickable { onLaunch(appInfo) }
                        )
                        Text(
                            text = appInfo.label,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIconItem(appInfo: AppInfo, onLaunch: (AppInfo) -> Unit, availableWidth: androidx.compose.ui.unit.Dp) {
    val iconSize = if (availableWidth < 110.dp) 36.dp else 44.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Image(
            painter = rememberDrawablePainter(drawable = appInfo.icon),
            contentDescription = null,
            modifier = Modifier.size(iconSize).clip(CircleShape).clickable { onLaunch(appInfo) }
        )
        Text(
            text = appInfo.label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
        )
    }
}

@Composable
private fun ClipboardListContent(items: List<SmartClipboardEntity>, viewModel: ServiceViewModel, hostView: android.view.View) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val availableHeight = maxHeight

        if (items.isEmpty()) {
            Text(stringResource(R.string.smart_clipboard_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = availableHeight)
                    .wrapContentHeight(Alignment.CenterVertically)
            ) {
                items(items, key = { it.id }) { item ->
                    SwipeToDeleteContainer(onDelete = { viewModel.deleteSmartClipboardItem(item) }) {
                        ClipboardPreviewCard(item, viewModel, hostView)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeToDeleteContainer(onDelete: () -> Unit, content: @Composable () -> Unit) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX < -300f) {
                            onDelete()
                        } else {
                            offsetX = 0f
                        }
                    },
                    onDragCancel = { offsetX = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    offsetX = (offsetX + dragAmount).coerceAtMost(0f)
                }
            }
    ) {
        content()
    }
}

@Composable
private fun ClipboardPreviewCard(item: SmartClipboardEntity, viewModel: ServiceViewModel, hostView: android.view.View) {
    val imageBitmap by produceState<ImageBitmap?>(null, item.imagePath, item.id) {
        value = item.imagePath?.let { path ->
            viewModel.loadPreviewBitmap(path, 320, 180)
        }
    }
    val isFileMissing = (item.type == SmartClipboardEntity.TYPE_IMAGE || item.type == SmartClipboardEntity.TYPE_FILE) && item.imagePath != null && imageBitmap == null
    if (isFileMissing && item.imagePath != null) {
        LaunchedEffect(item.id, item.imagePath) {
            kotlinx.coroutines.delay(500)
            viewModel.deleteMissingPreview(item)
        }
    }
    val timestamp = remember(item.createdAt) {
        DateUtils.getRelativeTimeSpanString(item.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    val titleText = when (item.type) {
        SmartClipboardEntity.TYPE_TEXT -> item.text ?: ""
        else -> item.fileName ?: item.mimeType ?: ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .pointerInput(item.id) { detectTapGestures(onLongPress = { viewModel.startSmartClipboardDrag(hostView, item) }) }
            .clickable { viewModel.copySmartClipboardItem(item) }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 90.dp),
            contentAlignment = Alignment.Center
        ) {
            if (item.type == SmartClipboardEntity.TYPE_IMAGE && imageBitmap != null) {
                Image(bitmap = imageBitmap!!, contentDescription = stringResource(R.string.smart_clipboard_image_description), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                    Icon(
                        painterResource(if (item.type == SmartClipboardEntity.TYPE_FILE) R.drawable.smart_clipboard_file_24 else R.drawable.smart_clipboard_text_24),
                        null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)
                    )
                    if (titleText.isNotBlank()) {
                        Text(
                            text = if (isFileMissing) stringResource(R.string.smart_clipboard_missing_file) else titleText,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            if (item.isPinned) {
                Icon(
                    painterResource(R.drawable.smart_clipboard_pin_24),
                    contentDescription = stringResource(R.string.smart_clipboard_pin_description),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
                )
            }
        }
        Text(
            text = timestamp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.toggleSmartClipboardItemPinned(item) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painterResource(R.drawable.smart_clipboard_pin_24),
                    contentDescription = if (item.isPinned) stringResource(R.string.smart_clipboard_unpin_description) else stringResource(R.string.smart_clipboard_pin_description),
                    tint = if (item.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = { viewModel.copySmartClipboardItem(item) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painterResource(R.drawable.smart_clipboard_copy_24),
                    contentDescription = stringResource(R.string.smart_clipboard_copy_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = { viewModel.shareSmartClipboardItem(item) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painterResource(R.drawable.smart_clipboard_share_24),
                    contentDescription = stringResource(R.string.smart_clipboard_share_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = { viewModel.deleteSmartClipboardItem(item) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painterResource(R.drawable.smart_clipboard_delete_24),
                    contentDescription = stringResource(R.string.smart_clipboard_delete_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

package com.libremobileos.sidebar.service

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarComposeView(
    viewModel: ServiceViewModel,
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

    val minSidebarWidth = 160.dp
    val minSidebarHeight = 168.dp

    val savedGeometry = remember { viewModel.getSidebarGeometry() }
    var sidebarWidth by remember { mutableStateOf(savedGeometry.first.dp) }
    var sidebarHeight by remember { mutableStateOf(savedGeometry.second.dp) }
    var verticalOffset by remember { mutableStateOf(savedGeometry.third) }

    var cardBounds by remember { mutableStateOf(Rect.Zero) }

    val maxScreenWidth = config.screenWidthDp.dp * 0.85f

    Box(
        modifier = Modifier
            .fillMaxSize()
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
        contentAlignment = Alignment.CenterEnd
    ) {
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val maxSidebarHeight = maxHeight * 0.95f
        val limit = (containerHeightPx - with(density) { sidebarHeight.toPx() }) / 2

        LaunchedEffect(limit, maxSidebarHeight) {
            var changed = false
            if (sidebarHeight > maxSidebarHeight) {
                sidebarHeight = maxSidebarHeight
                changed = true
            }
            if (verticalOffset < -limit || verticalOffset > limit) {
                verticalOffset = verticalOffset.coerceIn(-limit, limit)
                changed = true
            }
            if (changed) {
                viewModel.saveSidebarGeometry(sidebarWidth.value, sidebarHeight.value, verticalOffset)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16181D)),
            shape = RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp, topEnd = 0.dp, bottomEnd = 0.dp),
            modifier = Modifier
                .offset { IntOffset(0, verticalOffset.roundToInt()) }
                .width(sidebarWidth)
                .height(sidebarHeight)
                .onGloballyPositioned { cardBounds = it.boundsInRoot() }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                
                Column(modifier = Modifier.fillMaxSize().padding(start = 32.dp, end = 8.dp)) {
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (pagerState.currentPage == 0) Icons.Default.Apps else Icons.Default.ContentPaste,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                        IconButton(
                            onClick = { viewModel.openSidebarSettings(); closeSidebar() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Settings, null, Modifier.size(22.dp), Color.White.copy(alpha = 0.5f))
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
                            Box(modifier = Modifier.size(if (isSelected) 6.dp else 4.dp).clip(CircleShape).background(if (isSelected) Color.White else Color.White.copy(alpha = 0.3f)))
                            if (iteration < pagerState.pageCount - 1) Spacer(Modifier.width(6.dp))
                        }
                    }
                }


                Box(modifier = Modifier.align(Alignment.TopStart).size(48.dp).pointerInput(containerHeightPx) {
                    detectDragGestures(
                        onDragEnd = { viewModel.saveSidebarGeometry(sidebarWidth.value, sidebarHeight.value, verticalOffset) }
                    ) { change, dragAmount ->
                        change.consume()
                        val currentLimit = (containerHeightPx - with(density) { sidebarHeight.toPx() }) / 2
                        verticalOffset = (verticalOffset + dragAmount.y).coerceIn(-currentLimit, currentLimit)
                    }
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.OpenWith, null, Modifier.size(18.dp), Color.White.copy(alpha = 0.15f))
                }

                Box(modifier = Modifier.align(Alignment.BottomStart).size(48.dp).pointerInput(containerHeightPx) {
                    detectDragGestures(
                        onDragEnd = { viewModel.saveSidebarGeometry(sidebarWidth.value, sidebarHeight.value, verticalOffset) }
                    ) { change, dragAmount ->
                        change.consume()
                        with(density) {
                            sidebarWidth = (sidebarWidth - dragAmount.x.toDp()).coerceIn(minSidebarWidth, maxScreenWidth)
                            val newHeight = (sidebarHeight + (dragAmount.y.toDp() * 2)).coerceIn(minSidebarHeight, maxSidebarHeight)
                            sidebarHeight = newHeight
                            
                            val currentLimit = (containerHeightPx - newHeight.toPx()) / 2
                            verticalOffset = verticalOffset.coerceIn(-currentLimit, currentLimit)
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.edit_24px), null, Modifier.size(16.dp), Color.White.copy(alpha = 0.15f))
                }
            }
        }
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
            val iconSize = 44.dp // sidebar never narrows below 160dp, so 36dp branch is unreachable

            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            color = Color.White.copy(alpha = 0.9f),
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
            color = Color.White.copy(alpha = 0.9f),
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
            Text("Clipboard Empty", color = Color.Gray, fontSize = 12.sp)
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
                    ClipboardPreviewCard(item, { viewModel.startSmartClipboardDrag(hostView, item) }, { viewModel.copySmartClipboardItem(item) })
                }
            }
        }
    }
}

@Composable
private fun ClipboardPreviewCard(item: SmartClipboardEntity, onLongPress: () -> Boolean, onClick: () -> Unit) {
    val imageBitmap by produceState<ImageBitmap?>(null, item.imagePath) {
        value = withContext(Dispatchers.IO) {
            item.imagePath?.let { path ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .heightIn(min = 65.dp, max = 90.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (item.type == SmartClipboardEntity.TYPE_IMAGE && imageBitmap != null) {
            Image(bitmap = imageBitmap!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                Icon(
                    painterResource(if (item.type == SmartClipboardEntity.TYPE_FILE) R.drawable.smart_clipboard_file_24 else R.drawable.smart_clipboard_text_24),
                    null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(20.dp)
                )
                item.text?.let {
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

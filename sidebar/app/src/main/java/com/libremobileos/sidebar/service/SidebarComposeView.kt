package com.libremobileos.sidebar.service

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.room.SmartClipboardEntity

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
    val hostView = LocalView.current

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.widthIn(min = 72.dp, max = 320.dp)
    ) {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                Icon(
                    painter = rememberDrawablePainter(drawable = viewModel.allAppActivity.icon),
                    contentDescription = viewModel.allAppActivity.label,
                    modifier = Modifier
                        .size(50.dp)
                        .padding(8.dp)
                        .clickable {
                            launchApp(viewModel.allAppActivity)
                        }
                )
            }
            items(sidebarAppList) { appInfo ->
                Image(
                    painter = rememberDrawablePainter(drawable = appInfo.icon),
                    contentDescription = appInfo.label,
                    modifier = Modifier
                        .size(50.dp)
                        .padding(8.dp)
                        .clickable {
                            launchApp(appInfo)
                        }
                )
            }
            item {
                Icon(
                    painter = painterResource(R.drawable.edit_24px),
                    contentDescription = stringResource(R.string.sidebar_settings_description),
                    modifier = Modifier
                        .size(50.dp)
                        .padding(10.dp)
                        .clickable {
                            viewModel.openSidebarSettings()
                            closeSidebar()
                        }
                )
            }
            if (smartClipboardEnabled) {
                item {
                    Text(
                        text = stringResource(R.string.smart_clipboard_label),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp)
                    )
                }
                if (smartClipboardItems.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.smart_clipboard_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    items(
                        items = smartClipboardItems,
                        key = { item -> item.id }
                    ) { item ->
                        SmartClipboardListItem(
                            item = item,
                            onCopy = { viewModel.copySmartClipboardItem(item) },
                            onShare = { viewModel.shareSmartClipboardItem(item) },
                            onDelete = { viewModel.deleteSmartClipboardItem(item) },
                            onStartDrag = { viewModel.startSmartClipboardDrag(hostView, item) },
                            onPin = { viewModel.toggleSmartClipboardItemPinned(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartClipboardListItem(
    item: SmartClipboardEntity,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onStartDrag: () -> Boolean,
    onPin: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartClipboardPreview(
                item = item,
                onLongPress = onStartDrag
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = when (item.type) {
                        SmartClipboardEntity.TYPE_IMAGE -> stringResource(R.string.smart_clipboard_image)
                        else -> item.text.orEmpty()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = remember(item.createdAt) {
                        DateUtils.getRelativeTimeSpanString(
                            item.createdAt,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmartClipboardAction(
                        iconRes = R.drawable.smart_clipboard_copy_24,
                        contentDescription = stringResource(R.string.smart_clipboard_copy_description),
                        onClick = onCopy
                    )
                    SmartClipboardAction(
                        iconRes = R.drawable.smart_clipboard_share_24,
                        contentDescription = stringResource(R.string.smart_clipboard_share_description),
                        onClick = onShare
                    )
                    SmartClipboardAction(
                        iconRes = R.drawable.smart_clipboard_delete_24,
                        contentDescription = stringResource(R.string.smart_clipboard_delete_description),
                        onClick = onDelete
                    )
                    SmartClipboardAction(
                        iconRes = R.drawable.smart_clipboard_pin_24,
                        contentDescription = stringResource(R.string.smart_clipboard_pin_description),
                        onClick = onPin,
                        tint = if (item.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartClipboardPreview(
    item: SmartClipboardEntity,
    onLongPress: () -> Boolean
) {
    val imageBitmap = remember(item.imagePath) {
        item.imagePath?.let {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 4
            }
            BitmapFactory.decodeFile(it, opts)?.asImageBitmap()
        }
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .size(64.dp)
            .pointerInput(item.id) {
                detectTapGestures(
                    onLongPress = {
                        onLongPress()
                    }
                )
            }
    ) {
        if (item.type == SmartClipboardEntity.TYPE_IMAGE && imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = stringResource(R.string.smart_clipboard_image_description),
                modifier = Modifier.size(64.dp),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                painter = painterResource(
                    if (item.type == SmartClipboardEntity.TYPE_IMAGE) {
                        R.drawable.smart_clipboard_image_24
                    } else if (item.type == SmartClipboardEntity.TYPE_FILE) {
                        R.drawable.smart_clipboard_file_24
                    } else {
                        R.drawable.smart_clipboard_text_24
                    }
                ),
                contentDescription = if (item.type == SmartClipboardEntity.TYPE_IMAGE) {
                    stringResource(R.string.smart_clipboard_image_description)
                } else if (item.type == SmartClipboardEntity.TYPE_FILE) {
                    stringResource(R.string.smart_clipboard_file_description)
                } else {
                    stringResource(R.string.smart_clipboard_text_description)
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(32.dp)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun SmartClipboardAction(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.LocalContentColor.current
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint
        )
    }
}

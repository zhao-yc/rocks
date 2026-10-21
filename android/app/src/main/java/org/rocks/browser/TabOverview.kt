package org.rocks.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.roundToInt
import org.rocks.browser.core.BrowserData
import org.rocks.browser.core.BrowserTab

@Composable
internal fun TabOverview(data: BrowserData, onSelect: (String) -> Unit, onClose: (String) -> Unit,
    onNew: () -> Unit, onDone: () -> Unit) {
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = data.tabs.indexOfFirst { it.id == data.activeTabId }.coerceAtLeast(0))
    Dialog(onDismissRequest = onDone,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("tab-overview"), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.tabs), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(vertical = 20.dp))
                LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), state = grid, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(data.tabs, key = { it.id }) { tab ->
                        TabCard(tab, tab.id == data.activeTabId, onSelect = { onSelect(tab.id) }, onClose = { onClose(tab.id) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onNew, modifier = Modifier.testTag("add-tab")) {
                        Icon(Icons.Default.Add, stringResource(R.string.new_tab))
                    }
                    Text(stringResource(R.string.tab_count, data.tabs.size))
                    TextButton(onClick = onDone, modifier = Modifier.testTag("done-tabs")) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }
}

@Composable
private fun TabCard(tab: BrowserTab, active: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    var distance by remember(tab.id) { mutableFloatStateOf(0f) }
    var dragging by remember(tab.id) { mutableStateOf(false) }
    val offset by animateFloatAsState(distance, animationSpec = if (dragging) snap() else spring(), label = "tab-swipe")
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val close by rememberUpdatedState(onClose)
    val closeLabel = stringResource(R.string.close_tab, tab.title)
    Card(onClick = onSelect, border = BorderStroke(if (active) 2.dp else 1.dp,
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.testTag("tab-card-${tab.id}").offset { IntOffset(offset.roundToInt(), 0) }
            .semantics { selected = active; customActions = listOf(CustomAccessibilityAction(closeLabel) { onClose(); true }) }
            .pointerInput(tab.id, threshold) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; distance = 0f },
                    onDragCancel = { dragging = false; distance = 0f },
                    onDragEnd = {
                        val shouldClose = abs(distance) > threshold
                        dragging = false; distance = 0f
                        if (shouldClose) close()
                    },
                    onHorizontalDrag = { change, amount -> change.consume(); distance += amount },
                )
            }) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tab.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = onClose, modifier = Modifier.testTag("close-tab")) { Icon(Icons.Default.Close, closeLabel) }
        }
        // 使用域名卡片而非网页截图，避免收集或留存页面中的敏感内容。
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().aspectRatio(1.1f).padding(8.dp)) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (tab.url.isEmpty()) Icon(Icons.Default.Home, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                else Text(pageHost(tab.url).take(1).uppercase(), style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(if (tab.url.isEmpty()) stringResource(R.string.home) else pageHost(tab.url),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

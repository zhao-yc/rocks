package org.rocks.geckodemo

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.rocks.browser.core.SearchEngine

internal enum class LibraryPage(val label: Int) {
    BOOKMARKS(R.string.bookmarks), HISTORY(R.string.history), DOWNLOADS(R.string.downloads), SETTINGS(R.string.settings)
}

@Composable
internal fun LibraryPanel(model: BrowserModel, initial: LibraryPage, dismiss: () -> Unit) {
    var page by remember(initial) { mutableStateOf(initial) }
    var bookmarkQuery by remember { mutableStateOf("") }
    var historyQuery by remember { mutableStateOf("") }
    var clearingHistory by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().heightIn(max = 650.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            LibraryPage.entries.forEach { item ->
                TextButton(onClick = { page = item }) {
                    Text(stringResource(item.label), fontWeight = if (page == item) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        if (page == LibraryPage.SETTINGS) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                DefaultBrowserSetting()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.search_engine), style = MaterialTheme.typography.titleMedium)
                SearchEngine.entries.forEach { engine ->
                    Row(Modifier.fillMaxWidth().clickable { model.setSearch(engine) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = model.searchEngine == engine, onClick = { model.setSearch(engine) })
                        Text(engine.label)
                    }
                }
                Text(stringResource(R.string.desktop_note), Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.persistence_note), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.version_text), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelMedium)
            }
        } else if (page == LibraryPage.DOWNLOADS) {
            Text(stringResource(R.string.download_session_note), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 470.dp).padding(bottom = 24.dp)) {
                if (model.files.tasks.isEmpty()) item { EmptyLibrary() }
                items(model.files.tasks.toList()) { task ->
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(task.filename, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(when (task.status) {
                            "complete" -> R.string.download_complete
                            "failed" -> R.string.download_incomplete
                            "cancelled" -> R.string.download_cancelled
                            else -> R.string.download_running
                        }), style = MaterialTheme.typography.labelSmall)
                        Text("${task.bytes / 1024} KB" + if (task.total > 0) " / ${task.total / 1024} KB" else "", style = MaterialTheme.typography.labelSmall)
                        if (task.status == "running") {
                            if (task.total > 0) LinearProgressIndicator(progress = { (task.bytes.toFloat() / task.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = { task.job?.cancel() }) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(page.label), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (page == LibraryPage.HISTORY && model.history.isNotEmpty())
                    TextButton(onClick = { clearingHistory = true }) { Text(stringResource(R.string.clear_history)) }
            }
            val entries = if (page == LibraryPage.BOOKMARKS) model.bookmarks.toList() else model.history.toList()
            val query = if (page == LibraryPage.BOOKMARKS) bookmarkQuery else historyQuery
            val filtered = LocalBrowserSearch.filter(entries, query)
            OutlinedTextField(value = query, onValueChange = {
                if (page == LibraryPage.BOOKMARKS) bookmarkQuery = it else historyQuery = it
            }, singleLine = true, label = { Text(stringResource(if (page == LibraryPage.BOOKMARKS)
                R.string.search_bookmarks else R.string.search_history)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = {
                        if (page == LibraryPage.BOOKMARKS) bookmarkQuery = "" else historyQuery = ""
                    }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_search)) }
                }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(bottom = 24.dp)) {
                if (filtered.isEmpty()) item { EmptyLibrary(noMatches = entries.isNotEmpty()) }
                items(filtered, key = { it.url }) { record ->
                    ListItem(headlineContent = { Text(record.title.ifBlank { record.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(record.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            if (page == LibraryPage.BOOKMARKS) IconButton(onClick = { model.removeBookmark(record.url) }) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.remove_bookmark))
                            }
                        }, modifier = Modifier.clickable { model.navigate(record.url); dismiss() })
                }
            }
        }
    }
    if (clearingHistory) AlertDialog(onDismissRequest = { clearingHistory = false },
        title = { Text(stringResource(R.string.clear_history)) },
        text = { Text(stringResource(R.string.clear_history_note)) },
        confirmButton = { TextButton(onClick = { model.clearHistory(); clearingHistory = false }) {
            Text(stringResource(R.string.clear_all_history))
        } },
        dismissButton = { TextButton(onClick = { clearingHistory = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun EmptyLibrary(noMatches: Boolean = false) {
    Text(stringResource(if (noMatches) R.string.library_no_matches else R.string.library_empty),
        Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun TabOverview(model: BrowserModel, onNewTab: () -> Unit, dismiss: () -> Unit) {
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = model.tabs.indexOfFirst { it.id == model.selectedId }.coerceAtLeast(0))
    var closingOtherTabsFor by remember { mutableStateOf<Long?>(null) }
    val selectedLabel = stringResource(R.string.current_tab)
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tabs), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = { closingOtherTabsFor = model.selectedId }, enabled = model.tabs.size > 1) {
                        Text(stringResource(R.string.close_others))
                    }
                    IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.close_tabs)) }
                }
                // 撤销入口位于总览自己的窗口内，避免主页面的 Snackbar 被全屏 Dialog 遮挡。
                model.latestClosedTab?.let { closed ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.last_closed_tab, closed.title.ifBlank { displayAddress(closed.url) }),
                            Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { model.reopenClosedTab(closed.closureId) }) { Text(stringResource(R.string.undo_close)) }
                    }
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), state = gridState, modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(model.tabs.toList(), key = { it.id }) { tab ->
                        Card(onClick = { model.selectTab(tab.id); dismiss() },
                            modifier = Modifier.semantics {
                                selected = model.selectedId == tab.id
                                if (model.selectedId == tab.id) stateDescription = selectedLabel
                            },
                            colors = CardDefaults.cardColors(containerColor = if (model.selectedId == tab.id)
                                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Language, null, Modifier.padding(12.dp).size(20.dp))
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { model.closeTab(tab.id, TabCloseCause.USER, showUndoNotice = false) }) {
                                    Icon(Icons.Outlined.Close, stringResource(R.string.close_named_tab,
                                        tab.title.ifBlank { if (tab.url == DemoPolicy.HOME) stringResource(R.string.home) else displayAddress(tab.url) }))
                                }
                            }
                            Column(Modifier.height(95.dp).padding(horizontal = 14.dp)) {
                                Text(tab.title.ifBlank { if (tab.url == DemoPolicy.HOME) stringResource(R.string.home) else tab.url }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                Text(if (tab.url == DemoPolicy.HOME) stringResource(R.string.home) else tab.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (model.tabs.size >= DemoPolicy.MAX_TABS) Text(stringResource(R.string.tab_limit),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Button(onClick = onNewTab, enabled = model.tabs.size < DemoPolicy.MAX_TABS,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Icon(Icons.Outlined.Add, null); Text(stringResource(R.string.new_tab))
                }
            }
        }
    }
    if (closingOtherTabsFor != null) AlertDialog(onDismissRequest = { closingOtherTabsFor = null },
        title = { Text(stringResource(R.string.close_others_title, (model.tabs.size - 1).coerceAtLeast(0))) },
        text = { Text(stringResource(R.string.close_others_note)) },
        confirmButton = { TextButton(onClick = {
            // 确认期间若当前标签被引擎切换，旧确认不作用于新的选中页。
            if (model.selectedId == closingOtherTabsFor) model.closeOtherTabs()
            closingOtherTabsFor = null
        }) { Text(stringResource(R.string.close_others)) } },
        dismissButton = { TextButton(onClick = { closingOtherTabsFor = null }) { Text(stringResource(R.string.cancel)) } })
}

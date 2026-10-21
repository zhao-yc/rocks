package org.rocks.geckodemo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    Column(Modifier.fillMaxWidth().heightIn(max = 650.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            LibraryPage.entries.forEach { item ->
                TextButton(onClick = { page = item }) {
                    Text(stringResource(item.label), fontWeight = if (page == item) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        if (page == LibraryPage.SETTINGS) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.search_engine), style = MaterialTheme.typography.titleMedium)
                SearchEngine.entries.forEach { engine ->
                    Row(Modifier.fillMaxWidth().clickable { model.setSearch(engine) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = model.searchEngine == engine, onClick = { model.setSearch(engine) })
                        Text(engine.label)
                    }
                }
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
                    TextButton(onClick = model::clearHistory) { Text(stringResource(R.string.clear_history)) }
            }
            val entries = if (page == LibraryPage.BOOKMARKS) model.bookmarks.toList() else model.history.toList()
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(bottom = 24.dp)) {
                if (entries.isEmpty()) item { EmptyLibrary() }
                items(entries, key = { it.url }) { record ->
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
}

@Composable
private fun EmptyLibrary() { Text(stringResource(R.string.library_empty), Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
internal fun TabOverview(model: BrowserModel, dismiss: () -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tabs), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = { model.closeOtherTabs() }) { Text(stringResource(R.string.close_others)) }
                    IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.close_panel)) }
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(model.tabs.toList(), key = { it.id }) { tab ->
                        Card(onClick = { model.selectTab(tab.id); dismiss() },
                            colors = CardDefaults.cardColors(containerColor = if (model.selectedId == tab.id)
                                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Language, null, Modifier.padding(12.dp).size(20.dp))
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { model.closeTab(tab.id) }) { Icon(Icons.Outlined.Close, stringResource(R.string.close_tab)) }
                            }
                            Column(Modifier.height(95.dp).padding(horizontal = 14.dp)) {
                                Text(tab.title.ifBlank { if (tab.url == DemoPolicy.HOME) stringResource(R.string.home) else tab.url }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                Text(if (tab.url == DemoPolicy.HOME) stringResource(R.string.home) else tab.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Button(onClick = { model.newTab(DemoPolicy.HOME); dismiss() }, Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Icon(Icons.Outlined.Add, null); Text(stringResource(R.string.new_tab))
                }
            }
        }
    }
}

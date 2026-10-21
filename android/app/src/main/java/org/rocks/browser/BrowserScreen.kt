package org.rocks.browser

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import org.rocks.browser.core.*

private enum class BrowserPanel { TABS, BOOKMARKS, HISTORY, SETTINGS }

/** 共享交互约定：网页占满内容区，导航与标签操作集中在拇指可达的底部。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(activity: MainActivity, model: BrowserViewModel = viewModel()) {
    var pendingExternal by remember { mutableStateOf<Uri?>(null) }
    var pendingDownload by remember { mutableStateOf<DownloadRequest?>(null) }
    val engine = remember(activity, model) { BrowserEngine(activity, model,
        external = { pendingExternal = it }, requestDownload = { pendingDownload = it }) }
    var panel by rememberSaveable { mutableStateOf<BrowserPanel?>(null) }
    var menu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingTabId by rememberSaveable { mutableStateOf<String?>(null) }
    var tabMenu by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val data = model.data
    val active = data.activeTab
    val status = engine.statuses[active.id] ?: PageStatus()

    fun dismissAddress() { editing = false; focusManager.clearFocus() }
    fun openPanel(value: BrowserPanel) { dismissAddress(); menu = false; tabMenu = false; panel = value }
    fun newTab() {
        if (data.tabs.size >= MAX_TABS) model.showNotice(activity.getString(R.string.tab_limit, MAX_TABS))
        else { dismissAddress(); model.change { it.openTab() }; panel = null }
        menu = false; tabMenu = false
    }
    LaunchedEffect(active.id, model.ready) {
        if (model.ready && editing && editingTabId != active.id) dismissAddress()
    }
    DisposableEffect(engine, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) engine.setResumed(true)
            if (event == Lifecycle.Event.ON_PAUSE) engine.setResumed(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); engine.destroy() }
    }
    SideEffect { engine.prune(data.tabs); engine.syncActive() }
    BackHandler(enabled = engine.fullScreen != null || editing || panel != null || status.back) {
        when { engine.fullScreen != null -> engine.exitFullScreen(); editing -> dismissAddress(); panel != null -> panel = null
            else -> engine.back(active.id) }
    }

    if (!model.ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Scaffold(
        bottomBar = {
            Surface(tonalElevation = 4.dp) {
                Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp)) {
                    if (status.progress < 100 && active.url.isNotEmpty()) {
                        LinearProgressIndicator(progress = { status.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            BrowserAddressBar(active.id, active.url, status.progress, editing,
                                onEditingChange = { if (it) { editingTabId = active.id; editing = true } else dismissAddress() },
                                onNavigate = model::navigate,
                                onSwitchTab = { next -> dismissAddress(); model.change { it.selectAdjacentTab(next) } },
                                onReloadOrStop = { if (status.progress < 100) engine.stop(active.id) else engine.reload(active.id) })
                        }
                        if (!editing) Box {
                            ToolButton(Icons.Default.MoreHoriz, activity.getString(R.string.more_options)) { menu = true }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(activity.getString(R.string.new_tab)) }, onClick = ::newTab,
                                    leadingIcon = { Icon(Icons.Default.Add, null) })
                                DropdownMenuItem(text = { Text(activity.getString(R.string.home)) }, onClick = {
                                    menu = false; engine.home(active.id)
                                    model.change { it.updateTab(it.activeTabId, "", activity.getString(R.string.new_tab)) }
                                }, leadingIcon = { Icon(Icons.Default.Home, null) })
                                DropdownMenuItem(text = { Text(if (data.bookmarks.any { it.url == active.url }) activity.getString(R.string.remove_bookmark) else activity.getString(R.string.bookmark_page)) },
                                    enabled = active.url.isNotEmpty(), onClick = { menu = false; model.change { it.toggleBookmark(System.currentTimeMillis()) } },
                                    leadingIcon = { Icon(Icons.Default.BookmarkBorder, null) })
                                listOf(BrowserPanel.BOOKMARKS to R.string.bookmarks, BrowserPanel.HISTORY to R.string.history,
                                    BrowserPanel.SETTINGS to R.string.settings).forEach { (value, label) ->
                                    DropdownMenuItem(text = { Text(activity.getString(label)) }, onClick = { openPanel(value) })
                                }
                            }
                        }
                    }
                    if (!editing) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        ToolButton(Icons.AutoMirrored.Filled.ArrowBack, activity.getString(R.string.back), status.back) { engine.back(active.id) }
                        ToolButton(Icons.AutoMirrored.Filled.ArrowForward, activity.getString(R.string.forward), status.forward) { engine.forward(active.id) }
                        ToolButton(Icons.Default.Share, activity.getString(R.string.share_page), active.url.isNotEmpty()) { activity.share(active.url) }
                        ToolButton(Icons.Default.Bookmarks, activity.getString(R.string.bookmarks)) { openPanel(BrowserPanel.BOOKMARKS) }
                        Box {
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.testTag("tabs-button").combinedClickable(
                                    onClick = { openPanel(BrowserPanel.TABS) },
                                    onLongClickLabel = activity.getString(R.string.tab_actions), onLongClick = { tabMenu = true })) {
                                Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(activity.getString(R.string.tab_count, data.tabs.size), color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            DropdownMenu(expanded = tabMenu, onDismissRequest = { tabMenu = false }) {
                                DropdownMenuItem(text = { Text(activity.getString(R.string.new_tab)) }, onClick = ::newTab)
                                DropdownMenuItem(text = { Text(activity.getString(R.string.close_current_tab)) }, onClick = {
                                    tabMenu = false; model.change { it.closeTab(it.activeTabId) }
                                })
                                DropdownMenuItem(text = { Text(activity.getString(R.string.close_other_tabs)) }, enabled = data.tabs.size > 1,
                                    onClick = { tabMenu = false; model.change { it.closeOtherTabs() } })
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (active.url.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Rocks", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(activity.getString(R.string.tagline), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(activity.getString(R.string.home_hint), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(24.dp))
                    data.bookmarks.take(4).forEach { bookmark ->
                        TextButton(onClick = { model.navigate(bookmark.url) }) {
                            Text(bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    TextButton(onClick = { openPanel(BrowserPanel.BOOKMARKS) }) { Text(activity.getString(R.string.bookmarks)) }
                }
            } else {
                AndroidView(factory = { FrameLayout(it) }, modifier = Modifier.fillMaxSize(), update = { container ->
                    val web = engine.view(active)
                    if (web.parent !== container) {
                        (web.parent as? ViewGroup)?.removeView(web)
                        container.removeAllViews()
                        container.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                })
            }
            if (editing) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                .clickable { dismissAddress() }.testTag("dismiss-address"))
        }
    }

    if (panel == BrowserPanel.TABS) {
        TabOverview(data, onSelect = { id -> model.change { it.selectTab(id) }; panel = null },
            onClose = { id -> model.change { it.closeTab(id) } }, onNew = ::newTab, onDone = { panel = null })
    }
    if (panel != null && panel != BrowserPanel.TABS) {
        ModalBottomSheet(onDismissRequest = { panel = null }) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(activity.getString(when (panel) {
                        BrowserPanel.BOOKMARKS -> R.string.bookmarks
                        BrowserPanel.HISTORY -> R.string.history
                        else -> R.string.settings
                    }), style = MaterialTheme.typography.headlineSmall)
                    if (panel == BrowserPanel.HISTORY) TextButton(onClick = { model.change { it.copy(history = emptyList()) } }) { Text(activity.getString(R.string.clear_history)) }
                }
                when (panel) {
                    BrowserPanel.BOOKMARKS, BrowserPanel.HISTORY -> {
                        val records = if (panel == BrowserPanel.BOOKMARKS) data.bookmarks else data.history
                        if (records.isEmpty()) Text(activity.getString(R.string.empty_list), Modifier.padding(vertical = 24.dp))
                        LazyColumn {
                            items(records, key = { it.url }) { record ->
                                ListItem(headlineContent = { Text(record.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(record.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.clickable { model.navigate(record.url); panel = null },
                                    trailingContent = { ToolButton(Icons.Default.Close, activity.getString(R.string.remove_record, record.title)) {
                                        model.change { snapshot -> if (panel == BrowserPanel.BOOKMARKS) snapshot.copy(bookmarks = snapshot.bookmarks.filterNot { it.url == record.url })
                                            else snapshot.copy(history = snapshot.history.filterNot { it.url == record.url }) }
                                    } })
                            }
                        }
                    }
                    BrowserPanel.SETTINGS -> {
                        Text(activity.getString(R.string.search_engine), Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleMedium)
                        SearchEngine.entries.forEach { option ->
                            Row(Modifier.fillMaxWidth().clickable { model.change { it.copy(settings = BrowserSettings(option.id)) } }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = data.settings.searchEngine == option.id, onClick = { model.change { it.copy(settings = BrowserSettings(option.id)) } })
                                Text(option.label)
                            }
                        }
                        Text(activity.getString(R.string.data_notice),
                            Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(activity.getString(R.string.history_notice), style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Unit
                }
            }
        }
    }

    pendingExternal?.let { uri ->
        AlertDialog(onDismissRequest = { pendingExternal = null }, title = { Text(activity.getString(R.string.open_app_title)) }, text = { Text(uri.toString().take(1024)) },
            confirmButton = { TextButton(onClick = { pendingExternal = null; if (!activity.openExternal(uri)) model.showNotice(activity.getString(R.string.no_app)) }) { Text(activity.getString(R.string.open_app)) } },
            dismissButton = { TextButton(onClick = { pendingExternal = null }) { Text(activity.getString(R.string.cancel)) } })
    }
    pendingDownload?.let { item ->
        AlertDialog(onDismissRequest = { pendingDownload = null }, title = { Text(activity.getString(R.string.download_title)) },
            text = { Text(activity.getString(R.string.download_body, item.url.take(1024))) },
            confirmButton = { TextButton(onClick = { pendingDownload = null; model.showNotice(if (activity.download(item)) activity.getString(R.string.download_started) else activity.getString(R.string.download_failed)) }) { Text(activity.getString(R.string.download)) } },
            dismissButton = { TextButton(onClick = { pendingDownload = null }) { Text(activity.getString(R.string.cancel)) } })
    }
    model.notice?.let { message ->
        AlertDialog(onDismissRequest = model::clearNotice, title = { Text("Rocks") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = model::clearNotice) { Text(activity.getString(R.string.ok)) } })
    }
    engine.fullScreen?.let { view ->
        androidx.compose.ui.window.Dialog(onDismissRequest = engine::exitFullScreen,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            AndroidView(factory = { FrameLayout(it) }, modifier = Modifier.fillMaxSize(), update = { container ->
                if (view.parent !== container) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    container.removeAllViews(); container.addView(view, FrameLayout.LayoutParams(-1, -1))
                }
            })
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled) { Icon(icon, contentDescription = label) }
}

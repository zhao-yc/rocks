package org.rocks.browser

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import org.rocks.browser.core.*

/** 共享交互约定：网页占满内容区，导航与标签操作集中在拇指可达的底部。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(activity: MainActivity, model: BrowserViewModel = viewModel()) {
    var pendingExternal by remember { mutableStateOf<Uri?>(null) }
    var pendingDownload by remember { mutableStateOf<DownloadRequest?>(null) }
    val engine = remember(activity, model) { BrowserEngine(activity, model,
        external = { pendingExternal = it }, requestDownload = { pendingDownload = it }) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val data = model.data
    val active = data.activeTab
    val status = engine.statuses[active.id] ?: PageStatus()

    LaunchedEffect(active.id, active.url, focused) { if (!focused) address = active.url }
    DisposableEffect(engine, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) engine.setResumed(true)
            if (event == Lifecycle.Event.ON_PAUSE) engine.setResumed(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); engine.destroy() }
    }
    SideEffect { engine.prune(data.tabs) }
    BackHandler(enabled = engine.fullScreen != null || panel != null || status.back) {
        when { engine.fullScreen != null -> engine.exitFullScreen(); panel != null -> panel = null
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
                    OutlinedTextField(
                        value = address, onValueChange = { address = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).onFocusChanged { focused = it.isFocused },
                        placeholder = { Text(activity.getString(R.string.search_hint)) },
                        leadingIcon = {
                            Text(if (active.url.startsWith("https://")) "HTTPS" else if (active.url.startsWith("http://")) "HTTP" else "R",
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                        },
                        trailingIcon = {
                            ToolButton(if (status.progress < 100) Icons.Default.Close else Icons.Default.Refresh,
                                if (status.progress < 100) activity.getString(R.string.stop_loading) else activity.getString(R.string.reload), active.url.isNotEmpty()) {
                                if (status.progress < 100) engine.stop(active.id) else engine.reload(active.id)
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { model.navigate(address); focusManager.clearFocus() }),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        ToolButton(Icons.AutoMirrored.Filled.ArrowBack, activity.getString(R.string.back), status.back) { engine.back(active.id) }
                        ToolButton(Icons.AutoMirrored.Filled.ArrowForward, activity.getString(R.string.forward), status.forward) { engine.forward(active.id) }
                        ToolButton(Icons.Default.Home, activity.getString(R.string.home)) { engine.home(active.id); model.change { it.updateTab(it.activeTabId, "", activity.getString(R.string.new_tab)) } }
                        TextButton(modifier = Modifier.testTag("tabs-button"), onClick = { panel = "Tabs" }) { Text(activity.getString(R.string.tab_count, data.tabs.size)) }
                        Box {
                            ToolButton(Icons.Default.MoreHoriz, activity.getString(R.string.more_options)) { menu = true }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(activity.getString(R.string.new_tab)) }, onClick = {
                                    menu = false
                                    if (data.tabs.size >= MAX_TABS) model.showNotice(activity.getString(R.string.tab_limit, MAX_TABS))
                                    else model.change { it.openTab() }
                                }, leadingIcon = { Icon(Icons.Default.Add, null) })
                                DropdownMenuItem(text = { Text(if (data.bookmarks.any { it.url == active.url }) activity.getString(R.string.remove_bookmark) else activity.getString(R.string.bookmark_page)) },
                                    enabled = active.url.isNotEmpty(), onClick = { menu = false; model.change { it.toggleBookmark(System.currentTimeMillis()) } },
                                    leadingIcon = { Icon(Icons.Default.BookmarkBorder, null) })
                                listOf(activity.getString(R.string.bookmarks), activity.getString(R.string.history), activity.getString(R.string.settings)).forEach { name ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = { menu = false; panel = name })
                                }
                                DropdownMenuItem(text = { Text(activity.getString(R.string.share_page)) }, enabled = active.url.isNotEmpty(),
                                    onClick = { menu = false; activity.share(active.url) }, leadingIcon = { Icon(Icons.Default.Share, null) })
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
                    TextButton(onClick = { panel = activity.getString(R.string.bookmarks) }) { Text(activity.getString(R.string.bookmarks)) }
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
        }
    }

    if (panel != null) {
        ModalBottomSheet(onDismissRequest = { panel = null }) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (panel == "Tabs") activity.getString(R.string.tabs) else panel.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                    if (panel == "Tabs") TextButton(modifier = Modifier.testTag("add-tab"), onClick = {
                        if (data.tabs.size >= MAX_TABS) model.showNotice(activity.getString(R.string.tab_limit, MAX_TABS))
                        else { model.change { it.openTab() }; panel = null }
                    }) { Text(activity.getString(R.string.new_tab)) }
                    if (panel == activity.getString(R.string.history)) TextButton(onClick = { model.change { it.copy(history = emptyList()) } }) { Text(activity.getString(R.string.clear_history)) }
                }
                when (panel) {
                    "Tabs" -> LazyColumn {
                        items(data.tabs, key = { it.id }) { tab ->
                            ListItem(headlineContent = { Text(if (tab.id == active.id) "● ${tab.title}" else tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(tab.url.ifEmpty { activity.getString(R.string.home) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.clickable { model.change { it.selectTab(tab.id) }; panel = null },
                                trailingContent = { IconButton(modifier = Modifier.testTag("close-tab"), onClick = { model.change { it.closeTab(tab.id) } }) { Icon(Icons.Default.Close, activity.getString(R.string.close_tab, tab.title)) } })
                        }
                    }
                    activity.getString(R.string.bookmarks), activity.getString(R.string.history) -> {
                        val records = if (panel == activity.getString(R.string.bookmarks)) data.bookmarks else data.history
                        if (records.isEmpty()) Text(activity.getString(R.string.empty_list), Modifier.padding(vertical = 24.dp))
                        LazyColumn {
                            items(records, key = { it.url }) { record ->
                                ListItem(headlineContent = { Text(record.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(record.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.clickable { model.navigate(record.url); panel = null },
                                    trailingContent = { ToolButton(Icons.Default.Close, activity.getString(R.string.remove_record, record.title)) {
                                        model.change { snapshot -> if (panel == activity.getString(R.string.bookmarks)) snapshot.copy(bookmarks = snapshot.bookmarks.filterNot { it.url == record.url })
                                            else snapshot.copy(history = snapshot.history.filterNot { it.url == record.url }) }
                                    } })
                            }
                        }
                    }
                    activity.getString(R.string.settings) -> {
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

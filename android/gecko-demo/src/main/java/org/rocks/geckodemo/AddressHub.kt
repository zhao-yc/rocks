package org.rocks.geckodemo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.rocks.browser.core.PageRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** 地址编辑是独立覆盖层，分类、查询和输入焦点不再依靠键盘是否可见来推断。 */
@Composable
internal fun AddressHub(model: BrowserModel, suggestions: List<AddressSuggestion>, snackbar: SnackbarHostState,
    onSubmit: () -> Unit, onSuggestion: (AddressSuggestion) -> Unit, onClose: () -> Unit) {
    val state = model.addressHub
    val addressFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val panelTitle = stringResource(R.string.address_hub)
    var clearClosed by remember { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf(false) }
    LaunchedEffect(state.focusVersion, windowFocused) {
        // 旋转后的第一次组合可能早于新窗口获得焦点，此时请求键盘会被 Android 忽略。
        if (!windowFocused) return@LaunchedEffect
        when (state.input) {
            HubInput.NONE -> { focus.clearFocus(); keyboard?.hide() }
            HubInput.ADDRESS -> { addressFocus.requestFocus(); keyboard?.show() }
            else -> { searchFocus.requestFocus(); keyboard?.show() }
        }
    }
    Surface(Modifier.fillMaxSize().semantics { paneTitle = panelTitle }, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .navigationBarsPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            HubCategories(state, Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            Box(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth()) {
                if (state.showingMatches) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                        item { HubHeading(stringResource(R.string.local_matches)) }
                        if (suggestions.isEmpty()) item { HubEmpty(false, stringResource(R.string.no_local_matches)) }
                        items(suggestions, key = { it.url }) { item ->
                            ListItem(headlineContent = { Text(item.title.ifBlank { item.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(item.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { SiteIcon(model.icons, item.url, item.title, 40.dp) },
                                trailingContent = { if (item.tabId != null) Text(stringResource(R.string.switch_to_tab), style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button) { onSuggestion(item) })
                        }
                    }
                } else {
                    key(state.page) {
                        val saved = state.positions[state.page] ?: HubScrollPosition()
                        val list = rememberLazyListState(saved.index, saved.offset)
                        val page = state.page
                        LaunchedEffect(list) {
                            snapshotFlow { HubScrollPosition(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
                                .collect { state.positions[page] = it }
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (page) {
                                AddressHubPage.OVERVIEW -> overviewItems(model,
                                    open = { if (model.navigate(it)) onClose() }, clear = { clearClosed = true },
                                    restore = { if (model.reopenClosedTab(it)) onClose() })
                                else -> libraryItems(model, searchFocus,
                                    open = { if (model.navigate(it)) onClose() }, clearHistory = { clearHistory = true })
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                state.error?.let { Text(stringResource(it.label), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(Modifier.weight(1f), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .24f))) {
                        Row(Modifier.heightIn(min = 56.dp).padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(state.address, state::changeAddress,
                                Modifier.weight(1f).focusRequester(addressFocus).onFocusChanged {
                                    if (it.isFocused) state.focus(HubInput.ADDRESS)
                                }, singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                                decorationBox = { inner ->
                                    Box { if (state.address.text.isEmpty()) Text(stringResource(R.string.address_hint), color = MaterialTheme.colorScheme.onSurfaceVariant); inner() }
                                })
                            if (state.address.text.isNotEmpty()) IconButton(onClick = state::clearAddress) {
                                Icon(Icons.Outlined.Cancel, stringResource(R.string.clear_input))
                            } else Spacer(Modifier.width(12.dp))
                        }
                    }
                    FilledTonalIconButton(onClick = onClose, modifier = Modifier.size(56.dp), shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
    if (clearClosed) AlertDialog(onDismissRequest = { clearClosed = false },
        title = { Text(stringResource(R.string.clear_closed_tabs)) }, text = { Text(stringResource(R.string.clear_closed_note)) },
        confirmButton = { TextButton(onClick = { model.clearClosedTabs(); clearClosed = false }) { Text(stringResource(R.string.clear_all)) } },
        dismissButton = { TextButton(onClick = { clearClosed = false }) { Text(stringResource(R.string.cancel)) } })
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false },
        title = { Text(stringResource(R.string.clear_history)) }, text = { Text(stringResource(R.string.clear_history_note)) },
        confirmButton = { TextButton(onClick = { model.clearHistory(); clearHistory = false }) { Text(stringResource(R.string.clear_all_history)) } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text(stringResource(R.string.cancel)) } })
}

/** 分类栏始终提供文字、图标与选中语义，横屏也不依赖难以发现的滑动手势。 */
@Composable
private fun HubCategories(state: AddressHubState, modifier: Modifier) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(4.dp)) {
            AddressHubPage.entries.forEach { page ->
                val title = stringResource(when (page) {
                    AddressHubPage.OVERVIEW -> R.string.overview
                    AddressHubPage.BOOKMARKS -> R.string.bookmarks
                    AddressHubPage.HISTORY -> R.string.history
                })
                val selected = state.page == page
                Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .semantics { this.selected = selected; role = Role.Tab }
                    .clickable { state.browse(page) }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(when (page) { AddressHubPage.OVERVIEW -> Icons.Outlined.GridView
                        AddressHubPage.BOOKMARKS -> Icons.Outlined.Bookmarks
                        AddressHubPage.HISTORY -> Icons.Outlined.History }, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/** 收藏网格只显示现有书签；恢复列表通过记录 ID 打开所点的那一条。 */
private fun LazyListScope.overviewItems(model: BrowserModel, open: (String) -> Unit, clear: () -> Unit, restore: (Long) -> Unit) {
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HubHeading(stringResource(R.string.personal_favorites), Modifier.weight(1f))
            if (model.bookmarks.isNotEmpty()) TextButton(onClick = { model.addressHub.browse(AddressHubPage.BOOKMARKS) }) {
                Text(stringResource(R.string.all_bookmarks))
            }
        }
    }
    if (model.bookmarks.isEmpty()) item { HubEmpty(true) }
    else item {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minimum = 80.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val columns = (maxWidth / minimum).toInt().coerceIn(2, 6)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                model.bookmarks.take(8).chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { record ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { open(record.url) }
                                .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                SiteIcon(model.icons, record.url, record.title, 64.dp)
                                Text(record.title.ifBlank { displayAddress(record.url) }, Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
                                    style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HubHeading(stringResource(R.string.recently_closed_tabs), Modifier.weight(1f))
            if (model.closedTabs.isNotEmpty()) TextButton(onClick = clear) { Text(stringResource(R.string.clear_all)) }
        }
    }
    if (model.closedTabs.isEmpty()) item { HubEmpty(false, stringResource(R.string.no_closed_tabs)) }
    items(if (model.addressHub.allClosed) model.closedTabs.toList() else model.closedTabs.take(3), key = { "closed-${it.closureId}" }) { entry ->
        RecordCard(model, PageRecord(entry.url, entry.title, 0), { restore(entry.closureId) })
    }
    if (model.closedTabs.size > 3) item {
        TextButton(onClick = { model.addressHub.allClosed = !model.addressHub.allClosed }) {
            Text(stringResource(if (model.addressHub.allClosed) R.string.show_less else R.string.show_all))
        }
    }
}

/** 书签保持原顺序，历史按日期分组；搜索时展开结果，清空查询后恢复原折叠状态。 */
private fun LazyListScope.libraryItems(model: BrowserModel, requester: FocusRequester, open: (String) -> Unit, clearHistory: () -> Unit) {
    val state = model.addressHub
    val bookmarks = state.page == AddressHubPage.BOOKMARKS
    val source = if (bookmarks) model.bookmarks.toList() else model.history.toList()
    val query = if (bookmarks) state.bookmarkQuery else state.historyQuery
    val results = LocalBrowserSearch.filter(source, query)
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HubHeading(stringResource(if (bookmarks) R.string.bookmarks else R.string.history), Modifier.weight(1f))
            if (!bookmarks && source.isNotEmpty()) TextButton(onClick = clearHistory) { Text(stringResource(R.string.clear_history)) }
        }
        OutlinedTextField(query, {
            if (bookmarks) state.bookmarkQuery = it else state.historyQuery = it
        }, Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged {
            if (it.isFocused) state.focus(if (bookmarks) HubInput.BOOKMARKS else HubInput.HISTORY)
        }, singleLine = true, shape = CircleShape,
            placeholder = { Text(stringResource(if (bookmarks) R.string.search_bookmarks else R.string.search_history)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = {
                if (bookmarks) state.bookmarkQuery = "" else state.historyQuery = ""
            }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_search)) } })
    }
    if (results.isEmpty()) item { HubEmpty(source.isEmpty()) }
    if (bookmarks) items(results, key = { it.url }) { record ->
        RecordCard(model, record, { open(record.url) }, remove = { model.removeBookmark(record.url) })
    } else HistoryDateSections.group(results).forEach { section ->
        item(key = "date-${section.date}") {
            val collapsed = query.isBlank() && state.collapsedDates[section.date] == true
            val expandedState = stringResource(if (collapsed) R.string.history_collapsed else R.string.history_expanded)
            val title = when (section.date) {
                LocalDate.now() -> stringResource(R.string.today)
                LocalDate.now().minusDays(1) -> stringResource(R.string.yesterday)
                else -> section.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            }
            TextButton(onClick = { state.collapsedDates[section.date] = state.collapsedDates[section.date] != true },
                enabled = query.isBlank(), modifier = Modifier.fillMaxWidth().semantics { stateDescription = expandedState }) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Icon(if (query.isBlank() && state.collapsedDates[section.date] == true) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, null)
            }
        }
        if (query.isNotBlank() || state.collapsedDates[section.date] != true) items(section.records, key = { it.url }) { record ->
            RecordCard(model, record, { open(record.url) })
        }
    }
}

@Composable
/** 单条记录共用真实图标与删除入口，整行点击只导航一次。 */
private fun RecordCard(model: BrowserModel, record: PageRecord, open: () -> Unit, remove: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(headlineContent = { Text(record.title.ifBlank { record.url }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(record.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { SiteIcon(model.icons, record.url, record.title, 40.dp) },
            trailingContent = { if (remove != null) IconButton(onClick = remove) { Icon(Icons.Outlined.Delete, stringResource(R.string.remove_bookmark)) } },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.clickable(role = Role.Button, onClick = open))
    }
}

@Composable
/** 段落标题同时提供读屏标题导航语义。 */
private fun HubHeading(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.padding(vertical = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
/** 空数据与过滤无结果使用不同提示，不显示没有实际操作的占位按钮。 */
private fun HubEmpty(empty: Boolean, message: String? = null) {
    Text(message ?: stringResource(if (empty) R.string.library_empty else R.string.library_no_matches),
        Modifier.fillMaxWidth().padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 只有在裁剪后的视口内才订阅图标；网址变化会销毁旧订阅，不能让迟到结果覆盖新行。 */
@Composable
private fun SiteIcon(repository: SiteIconRepository, url: String, title: String, size: Dp) {
    var visible by remember(url) { mutableStateOf(false) }
    var image by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(repository, url, visible, repository.active) { if (visible && repository.active) image = repository.load(url) }
    val label = title.trim().ifEmpty { displayAddress(url) }.ifEmpty { "?" }
    val initial = label.substring(0, label.offsetByCodePoints(0, 1)).uppercase()
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(MaterialTheme.colorScheme.primaryContainer)
        .onGloballyPositioned { visible = it.boundsInWindow().let { bounds -> bounds.width > 0 && bounds.height > 0 } },
        contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap == null) Text(initial, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        else Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().padding(4.dp))
    }
}

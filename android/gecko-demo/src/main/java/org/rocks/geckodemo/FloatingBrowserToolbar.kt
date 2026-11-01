package org.rocks.geckodemo

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberTouchExploration(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/** 地址编辑、导航和标签快捷操作共用底栏，长按入口同时暴露无障碍操作说明。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FloatingBrowserToolbar(
    model: BrowserModel, compact: Boolean, editing: Boolean,
    address: TextFieldValue, onAddressChange: (TextFieldValue) -> Unit, focusRequester: FocusRequester,
    addressModifier: Modifier, onAddress: () -> Unit, onSubmit: () -> Unit, onCancel: () -> Unit,
    addressError: AddressInputError?, suggestions: List<AddressSuggestion>, showingRecents: Boolean,
    onSuggestion: (AddressSuggestion) -> Unit, onClearAddress: () -> Unit,
    onTabs: () -> Unit, onMenu: () -> Unit, menuExpanded: Boolean, onDismissMenu: () -> Unit,
    tabMenuExpanded: Boolean, onTabMenu: () -> Unit, onDismissTabMenu: () -> Unit, onNewTab: () -> Unit,
    onExtensions: () -> Unit, onLibrary: (LibraryPage) -> Unit, onSitePermissions: () -> Unit, onShare: () -> Unit,
    onBarHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = model.current
    val host = displayAddress(current.url)
    val expandLabel = stringResource(R.string.expand_toolbar)
    val editLabel = stringResource(R.string.edit_address)
    val stateLabel = stringResource(if (compact) R.string.toolbar_compact else R.string.toolbar_expanded)
    BoxWithConstraints(modifier, contentAlignment = Alignment.BottomCenter) {
        val availableHeight = maxHeight
        val compactWidth = (host.length * 7 + 48).coerceIn(144, 220).dp.coerceAtMost(maxWidth)
        val width by animateDpAsState(if (compact) compactWidth else maxWidth,
            spring(stiffness = 500f), label = "floating-toolbar-width")
        Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 建议独立于底栏遮挡高度；键盘或横屏压缩空间时优先保留输入框。
            if (editing && availableHeight > 160.dp) AddressSuggestions(suggestions, showingRecents, onSuggestion,
                Modifier.fillMaxWidth().heightIn(max = (availableHeight - 128.dp).coerceIn(0.dp, 280.dp)))
            Surface(modifier = Modifier.fillMaxWidth().onSizeChanged { onBarHeight(it.height) }
                .animateContentSize(spring(stiffness = 500f))
                .semantics { stateDescription = stateLabel },
                shape = RoundedCornerShape(if (editing) 24.dp else 30.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
                tonalElevation = 0.dp, shadowElevation = 0.dp) {
                if (model.finder.visible) {
                    FindInPageControls(model)
                } else if (editing) {
                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = address, onValueChange = onAddressChange, singleLine = true,
                            placeholder = { Text(stringResource(R.string.address_hint)) },
                            modifier = Modifier.weight(1f).focusRequester(focusRequester),
                            shape = RoundedCornerShape(20.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                            isError = addressError != null,
                            supportingText = if (addressError != null) { { Text(stringResource(addressError.label)) } } else null,
                            trailingIcon = {
                                if (address.text.isNotEmpty()) IconButton(onClick = onClearAddress) {
                                    Icon(Icons.Outlined.Close, stringResource(R.string.clear_input))
                                }
                            })
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    }
                } else if (compact) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(addressModifier)
                        .clickable(onClickLabel = expandLabel, role = Role.Button, onClick = onAddress)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(Icons.Outlined.Public, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(host, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = model::back, enabled = current.canGoBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                        }
                        Row(Modifier.weight(1f).heightIn(min = 48.dp).then(addressModifier)
                            .clickable(onClickLabel = editLabel, role = Role.Button, onClick = onAddress)
                            .padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(when {
                                current.recordingCamera -> Icons.Outlined.Videocam
                                current.recordingMicrophone -> Icons.Outlined.Mic
                                current.url.startsWith("https://") -> Icons.Outlined.Lock
                                else -> Icons.Outlined.Language
                            }, if (current.recordingCamera || current.recordingMicrophone) stringResource(R.string.recording_active) else null,
                                Modifier.size(15.dp), tint = if (current.recordingCamera || current.recordingMicrophone) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            Text(host, Modifier.weight(1f).padding(start = 7.dp), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                        IconButton(onClick = { if (current.loading) model.stopLoading() else model.reload() },
                            enabled = current.url != DemoPolicy.HOME) {
                            Icon(if (current.loading) Icons.Outlined.Close else Icons.Outlined.Refresh,
                                stringResource(if (current.loading) R.string.stop_loading else R.string.reload))
                        }
                        Box {
                            Box(Modifier.size(48.dp).combinedClickable(onClick = onTabs,
                                onLongClickLabel = stringResource(R.string.tab_actions), onLongClick = onTabMenu,
                                role = Role.Button), contentAlignment = Alignment.Center) {
                              BadgedBox(badge = { Badge { Text(model.tabs.size.toString()) } }) {
                                Icon(Icons.Outlined.Tab, stringResource(R.string.tabs))
                              }
                            }
                            DropdownMenu(expanded = tabMenuExpanded, onDismissRequest = onDismissTabMenu) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.new_tab)) },
                                    leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = onNewTab)
                                DropdownMenuItem(text = { Text(stringResource(R.string.close_tab)) },
                                    leadingIcon = { Icon(Icons.Outlined.Close, null) },
                                    onClick = { onDismissTabMenu(); model.closeTab(current.id, TabCloseCause.USER) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.reopen_closed_tab)) },
                                    leadingIcon = { Icon(Icons.Outlined.Restore, null) }, enabled = model.latestClosedTab != null,
                                    onClick = { onDismissTabMenu(); model.reopenClosedTab() })
                            }
                        }
                        Box {
                            IconButton(onClick = onMenu) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.more)) }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.new_tab)) },
                                    leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = onNewTab)
                                DropdownMenuItem(text = { Text(stringResource(R.string.reopen_closed_tab)) },
                                    leadingIcon = { Icon(Icons.Outlined.Restore, null) }, enabled = model.latestClosedTab != null,
                                    onClick = { onDismissMenu(); model.reopenClosedTab() })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(stringResource(R.string.forward)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null) },
                                    enabled = current.canGoForward,
                                    onClick = { onDismissMenu(); current.session.goForward() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.extensions)) },
                                    leadingIcon = { Icon(Icons.Outlined.Extension, null) }, onClick = onExtensions)
                                DropdownMenuItem(text = { Text(stringResource(R.string.find_in_page)) },
                                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, enabled = DemoPolicy.isWebUrl(current.url),
                                    onClick = { onDismissMenu(); model.finder.open(); model.expandToolbar() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.desktop_site)) },
                                    leadingIcon = { Icon(Icons.Outlined.DesktopWindows, null) },
                                    trailingIcon = { Checkbox(checked = current.desktop, onCheckedChange = null) },
                                    enabled = DemoPolicy.isWebUrl(current.url),
                                    onClick = { onDismissMenu(); model.toggleDesktop() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.site_permissions)) },
                                    leadingIcon = { Icon(Icons.Outlined.Security, null) }, enabled = DemoPolicy.isWebUrl(current.url),
                                    onClick = onSitePermissions)
                                val bookmarked = model.bookmarks.any { it.url == current.url }
                                DropdownMenuItem(text = { Text(stringResource(if (bookmarked) R.string.remove_bookmark else R.string.bookmark_current)) },
                                    enabled = DemoPolicy.isWebUrl(current.url),
                                    leadingIcon = { Icon(if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, null) },
                                    onClick = { onDismissMenu(); model.toggleBookmark() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.share)) },
                                    leadingIcon = { Icon(Icons.Outlined.Share, null) }, enabled = DemoPolicy.isWebUrl(current.url),
                                    onClick = { onDismissMenu(); onShare() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.home)) },
                                    leadingIcon = { Icon(Icons.Outlined.Home, null) },
                                    onClick = { onDismissMenu(); model.home(); model.expandToolbar() })
                                HorizontalDivider()
                                LibraryPage.entries.forEach { page ->
                                    DropdownMenuItem(text = { Text(stringResource(page.label)) }, onClick = { onLibrary(page) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

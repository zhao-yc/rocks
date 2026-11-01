package org.rocks.geckodemo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val AddressInputError.label: Int get() = when (this) {
    AddressInputError.EMPTY -> R.string.address_empty
    AddressInputError.INVALID_INPUT -> R.string.address_invalid
    AddressInputError.INVALID_URL -> R.string.address_invalid_url
    AddressInputError.UNSUPPORTED_SCHEME -> R.string.address_unsupported_scheme
}

/** 使用有界原生列表展示本机匹配结果，不读取网站图标或发送联想请求。 */
@Composable
internal fun AddressSuggestions(items: List<AddressSuggestion>, recents: Boolean,
    choose: (AddressSuggestion) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        LazyColumn {
            item {
                Text(stringResource(if (recents) R.string.recent_visits else R.string.local_matches),
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (items.isEmpty()) item {
                Text(stringResource(if (recents) R.string.no_recent_visits else R.string.no_local_matches),
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            items(items, key = { it.url }) { item ->
                ListItem(
                    headlineContent = { Text(item.title.ifBlank { item.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(item.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(when {
                        item.tabId != null -> Icons.Outlined.Tab
                        item.source == SuggestionSource.BOOKMARK -> Icons.Outlined.BookmarkBorder
                        else -> Icons.Outlined.History
                    }, null) },
                    trailingContent = {
                        if (item.tabId != null) Text(stringResource(R.string.switch_to_tab),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable(role = Role.Button,
                        onClickLabel = stringResource(if (item.tabId != null) R.string.switch_to_tab else R.string.open_page)) { choose(item) },
                )
            }
        }
    }
}

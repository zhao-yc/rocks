package org.rocks.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkActionsSheet(url: String, onDismiss: () -> Unit, onOpen: (background: Boolean) -> Unit,
    onCopy: () -> Unit, onShare: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(pageHost(url), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(url, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            ListItem(headlineContent = { Text(stringResource(R.string.open_link_new_tab)) },
                leadingContent = { Icon(Icons.Default.Add, null) },
                modifier = Modifier.testTag("link-open").clickable { onOpen(false) })
            ListItem(headlineContent = { Text(stringResource(R.string.open_link_background)) },
                leadingContent = { Icon(Icons.Default.Tab, null) },
                modifier = Modifier.testTag("link-background").clickable { onOpen(true) })
            ListItem(headlineContent = { Text(stringResource(R.string.copy_link)) },
                leadingContent = { Icon(Icons.Default.ContentCopy, null) }, modifier = Modifier.testTag("link-copy").clickable(onClick = onCopy))
            ListItem(headlineContent = { Text(stringResource(R.string.share_link)) },
                leadingContent = { Icon(Icons.Default.Share, null) }, modifier = Modifier.testTag("link-share").clickable(onClick = onShare))
        }
    }
}

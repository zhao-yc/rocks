package org.rocks.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Rocks 的苔绿、暖纸色与炭黑文字；操作习惯参考 Safari，保留自己的视觉。 */
private val rocksColors = lightColorScheme(
    primary = Color(0xFF35614B), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E9D7), onPrimaryContainer = Color(0xFF173C2B),
    secondary = Color(0xFF526451), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE7D7), onSecondaryContainer = Color(0xFF263C28),
    tertiary = Color(0xFF8B5F3D), onTertiary = Color.White,
    background = Color(0xFFF8F8F2), onBackground = Color(0xFF202822),
    surface = Color(0xFFFAFAF5), onSurface = Color(0xFF202822),
    surfaceVariant = Color(0xFFE1E7DC), onSurfaceVariant = Color(0xFF475248),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F4EC),
    surfaceContainer = Color(0xFFEBEFE5), surfaceContainerHigh = Color(0xFFE5EADD),
    surfaceContainerHighest = Color(0xFFDDE4D6), surfaceTint = Color(0xFF35614B),
    outline = Color(0xFF737E71), outlineVariant = Color(0xFFC4CDBD),
)

@Composable
internal fun RocksTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = rocksColors, content = content)
}

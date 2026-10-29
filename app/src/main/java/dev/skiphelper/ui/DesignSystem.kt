package dev.skiphelper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF087F6F), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF4EC), onPrimaryContainer = Color(0xFF085B50),
    secondary = Color(0xFF586C66), secondaryContainer = Color(0xFFEAF1ED),
    background = Color(0xFFF5F7F6), onBackground = Color(0xFF192B26),
    surface = Color.White, onSurface = Color(0xFF192B26),
    surfaceVariant = Color(0xFFEEF2F0), onSurfaceVariant = Color(0xFF687A73),
    outline = Color(0xFFB6C4BE), outlineVariant = Color(0xFFE3EAE6),
    error = Color(0xFFBA4141), errorContainer = Color(0xFFFFEBEB),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF77DCC1), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF164E42), onPrimaryContainer = Color(0xFFA3EED9),
    secondary = Color(0xFFADC6BA), secondaryContainer = Color(0xFF293B34),
    background = Color(0xFF101916), onBackground = Color(0xFFE3EDE7),
    surface = Color(0xFF1B2722), onSurface = Color(0xFFE3EDE7),
    surfaceVariant = Color(0xFF25352D), onSurfaceVariant = Color(0xFFA9BAB0),
    outline = Color(0xFF60756A), outlineVariant = Color(0xFF304239),
)

@Composable
fun SkipTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = base.copy(
            headlineLarge = base.headlineLarge.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp),
            headlineMedium = base.headlineMedium.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp),
            titleLarge = base.titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content,
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tone: Color = MaterialTheme.colorScheme.primary) {
    Box(modifier.size(44.dp).background(tone.copy(alpha = .10f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = tone)
    }
}

@Composable
fun StatusPill(text: String, positive: Boolean = true) {
    val tone = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.background(tone.copy(alpha = .09f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp).background(tone, CircleShape))
        Text(text, color = tone, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action); Icon(Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp)) }
    }
}

@Composable
fun ActionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 38.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon, Modifier.size(56.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

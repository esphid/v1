package dev.scalexy.esphid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tokens shared with the device console and the iOS app: graphite neutrals, amber indicator. */
data class Palette(
    val paper: Color,
    val surface: Color,
    val sunken: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val accentStrong: Color,
    val tint: Color,
    val onAccent: Color,
    val accentWash: Color,
    val ok: Color,
    val bad: Color,
    val dot: Color,
)

private val Light =
    Palette(
        paper = Color(0xFFF8F6F4), surface = Color(0xFFFEFEFC), sunken = Color(0xFFEDEBE8),
        line = Color(0xFFDBD9D5), lineStrong = Color(0xFFBBB7B2), ink = Color(0xFF211C18),
        ink2 = Color(0xFF544F4A), ink3 = Color(0xFF78746F), accent = Color(0xFFEF861B),
        accentStrong = Color(0xFFE06D00), tint = Color(0xFFB85500), onAccent = Color(0xFF23140A),
        accentWash = Color(0xFFFCE3C8), ok = Color(0xFF218A45), bad = Color(0xFFC22826),
        dot = Color(0xFFCDCAC6),
    )

private val Dark =
    Palette(
        paper = Color(0xFF110E0B), surface = Color(0xFF1A1613), sunken = Color(0xFF0A0806),
        line = Color(0xFF2F2A26), lineStrong = Color(0xFF4A443F), ink = Color(0xFFEEEBE5),
        ink2 = Color(0xFFB8B3AD), ink3 = Color(0xFF8D8883), accent = Color(0xFFF99B39),
        accentStrong = Color(0xFFED8725), tint = Color(0xFFF99B39), onAccent = Color(0xFF211208),
        accentWash = Color(0xFF3B220D), ok = Color(0xFF5AC576), bad = Color(0xFFF66D62),
        dot = Color(0xFF342F2B),
    )

val LocalPalette = staticCompositionLocalOf { Light }
val P: Palette
    @Composable get() = LocalPalette.current

val Mono = FontFamily.Monospace
val ReadoutStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp)
val ReadoutSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp)

@Composable
fun EspTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) Dark else Light
    val scheme =
        if (dark)
            darkColorScheme(
                primary = p.tint, onPrimary = p.onAccent, primaryContainer = p.accentWash,
                onPrimaryContainer = p.ink, secondary = p.ink2, background = p.paper,
                onBackground = p.ink, surface = p.paper, onSurface = p.ink,
                surfaceVariant = p.sunken, onSurfaceVariant = p.ink2, outline = p.lineStrong,
                outlineVariant = p.line, error = p.bad, surfaceContainer = p.surface,
                surfaceContainerLow = p.surface, surfaceContainerHigh = p.surface,
                surfaceContainerHighest = p.sunken, secondaryContainer = p.accentWash,
                onSecondaryContainer = p.ink,
            )
        else
            lightColorScheme(
                primary = p.tint, onPrimary = Color.White, primaryContainer = p.accentWash,
                onPrimaryContainer = p.ink, secondary = p.ink2, background = p.paper,
                onBackground = p.ink, surface = p.paper, onSurface = p.ink,
                surfaceVariant = p.sunken, onSurfaceVariant = p.ink2, outline = p.lineStrong,
                outlineVariant = p.line, error = p.bad, surfaceContainer = p.surface,
                surfaceContainerLow = p.surface, surfaceContainerHigh = p.surface,
                surfaceContainerHighest = p.sunken, secondaryContainer = p.accentWash,
                onSecondaryContainer = p.ink,
            )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// ---------- components ----------

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(12.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = P.accent, contentColor = P.onAccent,
                disabledContainerColor = P.accent.copy(alpha = 0.45f),
                disabledContentColor = P.onAccent.copy(alpha = 0.6f),
            ),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = P.onAccent, strokeWidth = 2.dp)
        else Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, P.lineStrong),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = P.surface, contentColor = P.ink),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Physical-looking key; `on` = latched sticky modifier. */
@Composable
fun Keycap(
    modifier: Modifier = Modifier,
    on: Boolean = false,
    minWidth: Int = 48,
    label: String? = null,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit = {},
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier =
            modifier
                .defaultMinSize(minWidth = minWidth.dp, minHeight = 44.dp)
                .clip(shape)
                .background(if (on) P.accent else if (pressed) P.sunken else P.surface)
                .border(1.dp, if (on) P.accentStrong else P.lineStrong, shape)
                .clickable(src, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
                .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides (if (on) P.onAccent else P.ink)
        ) { content() }
    }
}

@Composable
fun KeyText(text: String) =
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)

enum class Tone { Live, Wait, Bad, Off }

@Composable
fun toneColor(t: Tone) =
    when (t) {
        Tone.Live -> P.ok
        Tone.Wait -> P.accent
        Tone.Bad -> P.bad
        Tone.Off -> P.ink3
    }

@Composable
fun StatusDot(tone: Tone) {
    val c = toneColor(tone)
    Box(Modifier.size(14.dp).clip(CircleShape).background(c.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
    }
}

@Composable
fun StatusChip(tone: Tone, text: String) {
    Row(
        Modifier.height(26.dp).clip(RoundedCornerShape(50)).background(P.sunken).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(toneColor(tone)))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = P.ink2, maxLines = 1)
    }
}

@Composable
fun SignalBars(rssi: Int) {
    val n = when { rssi >= -55 -> 4; rssi >= -67 -> 3; rssi >= -78 -> 2; else -> 1 }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) Box(Modifier.width(3.dp).height((3 + i * 3).dp).clip(RoundedCornerShape(1.dp)).background(if (i <= n) P.ink2 else P.lineStrong))
    }
}

/** Rounded surface card with a hairline border; content stacked with 14dp gaps. */
@Composable
fun Card(modifier: Modifier = Modifier, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(P.surface).border(1.dp, P.line, shape).padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(start = 4.dp, top = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.ink2)

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) = Text(text, modifier, fontSize = 13.sp, color = P.ink2, lineHeight = 18.sp)

@Composable
fun ErrorLine(text: String?) {
    if (text != null) Text(text, color = P.bad, fontSize = 14.sp, lineHeight = 19.sp)
}

@Composable
fun ReadoutRow(label: String, value: String, tone: Color? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = P.ink2, fontSize = 15.sp, modifier = Modifier.padding(end = 12.dp))
        Spacer(Modifier.weight(1f))
        Text(value, style = ReadoutStyle, color = tone ?: P.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    placeholder: String? = null,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    visual: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = P.ink3, maxLines = 1) } },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        textStyle = if (mono) ReadoutStyle.copy(fontSize = 15.sp, color = P.ink) else TextStyle(fontSize = 16.sp, color = P.ink),
        keyboardOptions = keyboard,
        visualTransformation = visual,
        trailingIcon = trailing,
        shape = RoundedCornerShape(12.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = P.surface, unfocusedContainerColor = P.surface,
                unfocusedBorderColor = P.lineStrong, focusedBorderColor = P.tint,
                focusedLabelColor = P.tint, cursorColor = P.tint,
            ),
    )
}

/** Selectable card used for radio-mode choices. */
@Composable
fun ChoiceCard(selected: Boolean, title: String, detail: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) P.accentWash else P.surface)
            .border(1.dp, if (selected) P.accentStrong else P.lineStrong, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape)
                .border(2.dp, if (selected) P.accentStrong else P.ink3, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(P.accentStrong)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = if (enabled) P.ink else P.ink3)
            Text(detail, fontSize = 14.sp, color = P.ink2, lineHeight = 19.sp)
        }
    }
}

@Composable
fun ScreenHeader(title: String, message: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = P.ink, lineHeight = 33.sp)
        if (!message.isNullOrEmpty()) Text(message, fontSize = 16.sp, color = P.ink2, lineHeight = 22.sp)
    }
}

@Composable
fun ContentColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.widthIn(max = 560.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

internal val Figtree =
    FontFamily(
        Font(R.font.figtree_light, FontWeight.Light),
        Font(R.font.figtree_regular, FontWeight.Normal),
        Font(R.font.figtree_medium, FontWeight.Medium),
        Font(R.font.figtree_bold, FontWeight.Bold),
    )

private val OnInk = Color(0xFF0B1F5C)
private val SecondsYellow = Color(0xFFFFD43B)

internal fun text(size: Int, weight: FontWeight = FontWeight.Bold, color: Color = Color.White, align: TextAlign? = null) =
    TextStyle(color = color, fontFamily = Figtree, fontWeight = weight, fontSize = size.sp, lineHeight = (size * 1.15f).sp, textAlign = align ?: TextAlign.Unspecified)

/** Wall-clock millis, updated on each [periodMs] boundary. */
@Composable
internal fun rememberNow(periodMs: Long = 1000): Long {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(periodMs) {
    while (true) {
      now = System.currentTimeMillis()
      delay(periodMs - now % periodMs)
    }
  }
  return now
}

/** Hour, minute and second hand angles in turns (0..1) for [millis]. */
internal data class HandTurns(val hour: Float, val minute: Float, val second: Float)

internal fun handTurns(millis: Long, calendar: Calendar = Calendar.getInstance()): HandTurns {
  calendar.timeInMillis = millis
  val sec = calendar.get(Calendar.SECOND)
  val min = calendar.get(Calendar.MINUTE) + sec / 60f
  val hour = calendar.get(Calendar.HOUR) + min / 60f
  return HandTurns(hour / 12f, min / 60f, sec / 60f)
}

private fun DrawScope.hand(c: Offset, turns: Float, length: Float, width: Float, color: Color, tail: Float = 0f, outline: Color? = null) =
    rotate(turns * 360f, c) {
      if (outline != null) drawLine(outline, Offset(c.x, c.y + tail), Offset(c.x, c.y - length), width + 8f, StrokeCap.Round)
      drawLine(color, Offset(c.x, c.y + tail), Offset(c.x, c.y - length), width, StrokeCap.Round)
    }

// --- Portal face -------------------------------------------------------------------------------

/** What the Portal face's tiles do. */
class PortalActions(
    val photoFrame: () -> Unit,
    val heyMuse: () -> Unit,
    val volume: (Int) -> Unit,
    val dnd: () -> Unit,
    val screen: () -> Unit,
    val brightness: () -> Unit,
)

@Composable
fun PortalFace(
    backdrop: GlassBackdrop?,
    layout: Map<PortalSlot, Rect>,
    device: GlassDeviceState,
    keepAwake: Boolean,
    museReady: Boolean,
    actions: PortalActions,
) {
  val b = backdrop ?: return
  Box(Modifier.fillMaxSize().drawBehind { drawImage(b.sharp) }) {
    GlassTile(b, layout.getValue(PortalSlot.Photo), false, onTap = actions.photoFrame) { TileText(photoFrameTile) }
    GlassTile(b, layout.getValue(PortalSlot.Muse), false, enabled = museReady, onTap = actions.heyMuse) { MuseTileFace(museReady) }
    GlassTile(b, layout.getValue(PortalSlot.Clock), false, onTap = null) { AnalogTileClock() }
    GlassTile(b, layout.getValue(PortalSlot.Volume), false, onTap = null) { VolumeFace(device.volume, actions.volume) }
    val dnd = dndTile(device.dnd)
    GlassTile(b, layout.getValue(PortalSlot.Dnd), dnd.on, enabled = dnd.enabled, onTap = actions.dnd) { TileText(dnd) }
    val screen = screenTile(keepAwake)
    GlassTile(b, layout.getValue(PortalSlot.Screen), screen.on, onTap = actions.screen) { TileText(screen) }
    val brightness = brightnessTile(device.brightness)
    GlassTile(b, layout.getValue(PortalSlot.Brightness), brightness.on, enabled = brightness.enabled, onTap = actions.brightness) {
      TileText(brightness)
    }
  }
}

private val LocalInk = compositionLocalOf { Color.White }

@Composable
private fun GlassTile(
    backdrop: GlassBackdrop,
    rect: Rect,
    on: Boolean,
    enabled: Boolean = true,
    onTap: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
  val press = rememberPress()
  val density = LocalDensity.current
  val tap by rememberUpdatedState(onTap)
  // Remembered so a recomposition (a volume step, a DND toggle) keeps each tile's cached glass
  // instead of rebuilding every tile's paths and shaders.
  val glass = remember(backdrop, rect, on) { Modifier.glass(backdrop, rect, press, on) }
  Box(
      Modifier.absoluteOffset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
          .requiredSize(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
          .pressable(press, enabled = enabled && onTap != null) { tap?.invoke() }
          .then(glass)) {
        CompositionLocalProvider(LocalInk provides if (on) OnInk else Color.White) {
          Box(Modifier.fillMaxSize().alpha(if (enabled) 1f else 0.55f)) { content() }
        }
      }
}

@Composable
private fun TileText(tile: Tile) {
  val ink = LocalInk.current
  Column(Modifier.fillMaxSize().padding(28.dp)) {
    GlyphIcon(tile.glyph, ink)
    Spacer(Modifier.weight(1f))
    BasicText(tile.title, style = text(30, color = ink))
    Spacer(Modifier.height(6.dp))
    BasicText(tile.status, style = text(24, FontWeight.Medium, ink.copy(alpha = 0.78f)))
  }
}

@Composable
private fun MuseTileFace(ready: Boolean) {
  val muse = ImageBitmap.imageResource(R.drawable.glass_muse)
  Column(Modifier.fillMaxSize().padding(28.dp)) {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
      Image(muse, null, Modifier.size(176.dp), filterQuality = FilterQuality.None)
    }
    BasicText("Hey Muse", style = text(30))
    Spacer(Modifier.height(6.dp))
    BasicText(if (ready) "Tap to talk" else "Not installed", style = text(24, FontWeight.Medium, Color.White.copy(alpha = 0.78f)))
  }
}

@Composable
internal fun GlyphIcon(glyph: Glyph, ink: Color, size: Int = 44) {
  Canvas(Modifier.size(size.dp)) {
    val s = this.size.width / 44f
    val stroke = Stroke(3.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (glyph) {
      Glyph.Photo -> {
        drawRoundRect(ink, Offset(4 * s, 8 * s), Size(36 * s, 28 * s), CornerRadius(6 * s), style = stroke)
        drawCircle(ink, 3.5f * s, Offset(30 * s, 16 * s))
        drawPath(
            Path().apply {
              moveTo(6 * s, 33 * s)
              lineTo(17 * s, 21 * s)
              lineTo(26 * s, 30 * s)
              lineTo(31 * s, 25 * s)
              lineTo(38 * s, 32 * s)
            },
            ink,
            style = stroke)
      }
      Glyph.Moon -> {
        val moon =
            Path().apply {
              op(
                  Path().apply { addOval(Rect(Offset(22 * s, 22 * s), 17 * s)) },
                  Path().apply { addOval(Rect(Offset(32 * s, 14 * s), 15 * s)) },
                  PathOperation.Difference)
            }
        drawPath(moon, ink)
      }
      Glyph.Screen -> {
        drawRoundRect(ink, Offset(4 * s, 6 * s), Size(36 * s, 25 * s), CornerRadius(5 * s), style = stroke)
        drawLine(ink, Offset(15 * s, 38 * s), Offset(29 * s, 38 * s), 3.2f * s, StrokeCap.Round)
        drawLine(ink, Offset(22 * s, 31 * s), Offset(22 * s, 38 * s), 3.2f * s, StrokeCap.Round)
      }
      Glyph.Sun -> {
        drawCircle(ink, 8f * s, center)
        for (i in 0 until 8) rotate(i * 45f) { drawLine(ink, Offset(22 * s, 3.5f * s), Offset(22 * s, 8.5f * s), 3.2f * s, StrokeCap.Round) }
      }
    }
  }
}

@Composable
private fun AnalogTileClock() {
  val now = rememberNow()
  val calendar = remember { Calendar.getInstance() }
  Canvas(Modifier.fillMaxSize().padding(26.dp).graphicsLayer()) {
    val t = handTurns(now, calendar)
    val r = size.minDimension / 2
    val c = center
    for (i in 0 until 60) rotate(i * 6f, c) {
      val major = i % 5 == 0
      drawLine(
          Color.White.copy(alpha = if (major) 0.9f else 0.35f),
          Offset(c.x, c.y - r + 2),
          Offset(c.x, c.y - r + if (major) 16 else 8),
          if (major) 4f else 2f,
          StrokeCap.Round)
    }
    hand(c, t.hour, r * 0.5f, 9f, Color.White)
    hand(c, t.minute, r * 0.76f, 6f, Color.White)
    hand(c, t.second, r * 0.84f, 2.5f, SecondsYellow, tail = r * 0.16f)
    drawCircle(SecondsYellow, 6f, c)
    drawCircle(Color.White, 2.5f, c)
  }
}

@Composable
private fun VolumeFace(volume: VolumeLevel, onVolume: (Int) -> Unit) {
  Column(Modifier.fillMaxSize().padding(28.dp)) {
    Speaker()
    Spacer(Modifier.height(18.dp))
    BasicText("Volume", style = text(30))
    BasicText("Portal speaker", style = text(24, FontWeight.Medium, Color.White.copy(alpha = 0.78f)))
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
      Row(verticalAlignment = Alignment.Bottom) {
        BasicText("${volume.percent}", style = text(128, FontWeight.Light))
        BasicText("%", Modifier.padding(bottom = 22.dp), style = text(48, FontWeight.Light, Color.White.copy(alpha = 0.7f)))
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      RoundButton(plus = false) { onVolume(-1) }
      RoundButton(plus = true) { onVolume(+1) }
    }
  }
}

@Composable
private fun Speaker() {
  Canvas(Modifier.size(44.dp)) {
    val s = size.width / 44f
    drawPath(
        Path().apply {
          moveTo(4 * s, 16 * s)
          lineTo(12 * s, 16 * s)
          lineTo(22 * s, 7 * s)
          lineTo(22 * s, 37 * s)
          lineTo(12 * s, 28 * s)
          lineTo(4 * s, 28 * s)
          close()
        },
        Color.White)
    val stroke = Stroke(3.2f * s, cap = StrokeCap.Round)
    drawArc(Color.White, -50f, 100f, false, Offset(16 * s, 12 * s), Size(16 * s, 20 * s), style = stroke)
    drawArc(Color.White, -55f, 110f, false, Offset(14 * s, 4 * s), Size(26 * s, 36 * s), style = stroke)
  }
}

@Composable
private fun RoundButton(plus: Boolean, onTap: () -> Unit) {
  val press = rememberPress()
  Box(
      Modifier.size(100.dp).pressable(press, onTap = onTap).drawWithCache {
        val fill = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.12f)))
        val rim =
            androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.45f)),
                start = Offset.Zero,
                end = Offset(size.width, size.height))
        val r = size.minDimension / 2
        onDrawBehind {
          drawCircle(Color(0x22000A40), r, center + Offset(0f, 4f))
          drawCircle(fill, r)
          val p = press.progress.value
          if (p > 0f) drawCircle(Color.White.copy(alpha = 0.18f * p), r)
          drawCircle(rim, r - 0.75f, style = Stroke(1.5f))
          val arm = 18f
          drawLine(Color.White, center - Offset(arm, 0f), center + Offset(arm, 0f), 5f, StrokeCap.Round)
          if (plus) drawLine(Color.White, center - Offset(0f, arm), center + Offset(0f, arm), 5f, StrokeCap.Round)
        }
      })
}

// --- Numerals face -----------------------------------------------------------------------------

/** Giant 12, 3, 6 and 9 behind live hands on a flat color. Style 0 is bold, style 1 light. */
@Composable
fun NumeralsFace(palette: FacePalette, style: Int) {
  val ink = Color(palette.ink)
  val measurer = rememberTextMeasurer()
  val weight = if (style == 0) FontWeight.Bold else FontWeight.Light
  Box(
      Modifier.fillMaxSize().drawWithCache {
        val fontPx = 360.sp.toPx()
        val numeral = TextStyle(color = ink, fontFamily = Figtree, fontWeight = weight, fontSize = 360.sp, letterSpacing = (-12).sp)
        val twelve = measurer.measure("12", numeral)
        val three = measurer.measure("3", numeral)
        val six = measurer.measure("6", numeral)
        val nine = measurer.measure("9", numeral)
        val w = size.width
        val h = size.height
        // Place by the glyphs, not the line boxes: Figtree's figures stand 0.7 em above the baseline.
        val cap = 0.7f * fontPx
        onDrawBehind {
          drawRect(Color(palette.background))
          drawText(twelve, topLeft = Offset((w - twelve.size.width) / 2, 30f - (twelve.firstBaseline - cap)))
          drawText(six, topLeft = Offset((w - six.size.width) / 2, h - 78f - six.firstBaseline))
          drawText(nine, topLeft = Offset(0.13f * w, h / 2 - (nine.firstBaseline - cap / 2)))
          drawText(three, topLeft = Offset(0.87f * w - three.size.width, h / 2 - (three.firstBaseline - cap / 2)))
        }
      }) {
        val now = rememberNow()
        val calendar = remember { Calendar.getInstance() }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
          val t = handTurns(now, calendar)
          val c = center
          hand(c, t.hour, 210f, 22f, Color.White, outline = ink)
          hand(c, t.minute, 330f, 16f, Color.White, outline = ink)
          hand(c, t.second, 360f, 4f, Color.White, tail = 40f)
          drawCircle(ink, 18f, c)
          drawCircle(Color.White, 13f, c)
        }
      }
}

// --- Modular face ------------------------------------------------------------------------------

/** Date, an analog clock, today's events and the weather, on a flat color. */
@Composable
fun ModularFace(palette: FacePalette, style: Int, weather: GlassWeather?, agenda: List<AgendaItem>?, use24Hour: Boolean) {
  val ink = Color(palette.ink)
  val now = rememberNow(60_000)
  val date = Date(now)
  val heavy = if (style == 0) FontWeight.Bold else FontWeight.Light
  Box(Modifier.fillMaxSize().drawBehind { drawRect(Color(palette.background)) }) {
    Column(Modifier.offset(72.dp, 48.dp)) {
      BasicText(
          buildAnnotatedString {
            append(SimpleDateFormat("EEE", Locale.getDefault()).format(date))
            append(" ")
            withStyle(SpanStyle(color = ink)) { append(SimpleDateFormat("MMM", Locale.getDefault()).format(date)) }
          },
          style = text(54))
      BasicText(
          SimpleDateFormat("d", Locale.getDefault()).format(date),
          Modifier.offset(y = (-24).dp),
          style = TextStyle(color = ink, fontFamily = Figtree, fontWeight = heavy, fontSize = 250.sp, lineHeight = 250.sp, letterSpacing = (-8).sp))
    }
    ModularClock(ink, Modifier.offset(820.dp, 40.dp).size(340.dp))
    Column(Modifier.offset(72.dp, 452.dp).width(640.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
      when {
        agenda == null -> AgendaNote(ink, "No calendar yet", "Add one in Screensaver settings")
        agenda.isEmpty() -> AgendaNote(ink, "Nothing else today", "Enjoy the quiet")
        else -> agenda.take(3).forEach { AgendaRow(ink, it, use24Hour) }
      }
    }
    Column(Modifier.offset(820.dp, 440.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      if (weather == null) {
        BasicText("Weather", style = text(30))
        BasicText("Unavailable", style = text(28, FontWeight.Medium, ink))
      } else {
        BasicText(weather.city.ifBlank { "Home" }, style = text(30))
        BasicText("${weather.temp}°", style = TextStyle(color = ink, fontFamily = Figtree, fontWeight = heavy, fontSize = 150.sp, lineHeight = 150.sp, letterSpacing = (-4).sp))
        BasicText(weatherLabel(weather.code), style = text(28, FontWeight.Medium, ink))
        if (weather.hi != null && weather.lo != null) BasicText("H:${weather.hi}° L:${weather.lo}°", style = text(26, FontWeight.Medium, ink.copy(alpha = 0.85f)))
      }
    }
  }
}

@Composable
private fun AgendaNote(ink: Color, title: String, detail: String) {
  Column(Modifier.drawBehind { drawRect(ink, size = Size(6f, size.height)) }.padding(start = 18.dp)) {
    BasicText(title, style = text(30, color = ink))
    BasicText(detail, style = text(28, FontWeight.Medium, ink.copy(alpha = 0.85f)))
  }
}

@Composable
private fun AgendaRow(ink: Color, item: AgendaItem, use24Hour: Boolean) {
  val fmt = SimpleDateFormat(if (use24Hour) "H:mm" else "h:mm", Locale.getDefault())
  val ampm = SimpleDateFormat("a", Locale.getDefault())
  Column(Modifier.drawBehind { drawRect(ink, size = Size(6f, size.height)) }.padding(start = 18.dp)) {
    BasicText(
        buildAnnotatedString {
          if (item.allDay) append("All day")
          else {
            append(fmt.format(Date(item.start)))
            append("–")
            append(fmt.format(Date(item.end)))
            if (!use24Hour) withStyle(SpanStyle(fontSize = 20.sp)) { append(" " + ampm.format(Date(item.end))) }
          }
        },
        style = text(30, color = ink))
    BasicText(item.title, style = text(28, FontWeight.Medium, ink.copy(alpha = 0.85f)), maxLines = 1)
  }
}

@Composable
private fun ModularClock(ink: Color, modifier: Modifier) {
  val measurer = rememberTextMeasurer()
  Box(
      modifier.drawWithCache {
        val style = TextStyle(color = ink, fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 32.sp)
        val labels = (1..12).map { measurer.measure("$it", style) }
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        onDrawBehind {
          for (i in 0 until 60) rotate(i * 6f, c) {
            val major = i % 5 == 0
            drawLine(ink, Offset(c.x, c.y - r + 2), Offset(c.x, c.y - r + if (major) 18 else 9), if (major) 3f else 2f, StrokeCap.Round)
          }
          labels.forEachIndexed { i, l ->
            val a = Math.toRadians(((i + 1) * 30 - 90).toDouble())
            val x = c.x + cos(a).toFloat() * r * 0.75f
            val y = c.y + sin(a).toFloat() * r * 0.75f
            drawText(l, topLeft = Offset(x - l.size.width / 2, y - l.size.height / 2))
          }
        }
      }) {
        val now = rememberNow()
        val calendar = remember { Calendar.getInstance() }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
          val t = handTurns(now, calendar)
          val c = center
          val r = size.minDimension / 2
          hand(c, t.hour, r * 0.5f, 9f, ink)
          hand(c, t.minute, r * 0.76f, 6f, ink)
          hand(c, t.second, r * 0.88f, 2.5f, Color.White, tail = r * 0.12f)
          drawCircle(Color.White, 6f, c)
        }
      }
}

// --- Photo face --------------------------------------------------------------------------------

/** The photo frame's current picture with a large clock over it. */
@Composable
fun PhotoFace(backdrop: GlassBackdrop?, use24Hour: Boolean) {
  val now = rememberNow(1000)
  val date = Date(now)
  val shadow = Shadow(Color(0x66000000), Offset(0f, 8f), 36f)
  Box(Modifier.fillMaxSize().drawBehind { backdrop?.let { drawImage(it.sharp) } ?: drawRect(Color(0xFF202431)) }) {
    Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      BasicText(
          DateFormatter.format(date, "EEEEMMMMd"),
          style = text(34, color = Color.White.copy(alpha = 0.94f), align = TextAlign.Center).copy(shadow = shadow))
      BasicText(
          SimpleDateFormat(if (use24Hour) "H:mm" else "h:mm", Locale.getDefault()).format(date),
          style =
              TextStyle(
                  color = Color.White,
                  fontFamily = Figtree,
                  fontWeight = FontWeight.Bold,
                  fontSize = 300.sp,
                  lineHeight = 300.sp,
                  letterSpacing = (-10).sp,
                  shadow = shadow))
    }
  }
}

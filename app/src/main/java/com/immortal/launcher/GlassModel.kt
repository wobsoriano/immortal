/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.roundToInt

/** A background color and the ink drawn on it. */
data class FacePalette(val background: Long, val ink: Long)

/**
 * The Glass theme's faces, in carousel order. Each face declares what edit mode can change on it:
 * its color palettes and how many type styles it has. Edit mode reads this table instead of
 * branching per face.
 */
enum class GlassFace(val label: String, val palettes: List<FacePalette>, val styles: Int) {
  Numerals(
      "Numerals",
      listOf(
          FacePalette(0xFF3BE0B3, 0xFF0D211B),
          FacePalette(0xFFFF9F43, 0xFF2A1504),
          FacePalette(0xFF7AA7FF, 0xFF0B1736),
          FacePalette(0xFFF2EDE2, 0xFF1D1A14),
          FacePalette(0xFFFF7AA2, 0xFF2C0A16),
      ),
      styles = 2),
  Modular(
      "Modular",
      listOf(
          FacePalette(0xFFEE9CF3, 0xFF5B1E66),
          FacePalette(0xFF8FD0F5, 0xFF0F3F5C),
          FacePalette(0xFFF5CF8F, 0xFF5C3A0A),
          FacePalette(0xFFA9EEA0, 0xFF1F4D18),
          FacePalette(0xFFC3B5FF, 0xFF2F2366),
      ),
      styles = 2),
  Portal(
      "Portal",
      listOf(
          FacePalette(0xFF2357EC, 0xFFFFFFFF),
          FacePalette(0xFF6A3BEA, 0xFFFFFFFF),
          FacePalette(0xFF0E8C8C, 0xFFFFFFFF),
          FacePalette(0xFFE0533A, 0xFFFFFFFF),
      ),
      styles = 1),
  Photo("Photo", emptyList(), styles = 1);

  val editable: Boolean
    get() = palettes.size > 1 || styles > 1
}

/** What the user picked for one face in edit mode. */
data class FaceLook(val color: Int = 0, val style: Int = 0)

enum class GlassLayer {
  /** The shared face carousel. */
  Faces,
  /** Long-press edit mode: the face shrinks into a card beside a rail of buttons. */
  Edit,
  /** The personal Home Screen, swiped up over the current face. */
  Home,
}

sealed interface GlassEvent {
  /** A horizontal swipe; [step] is +1 for the next face, -1 for the previous one. */
  data class Swipe(val step: Int) : GlassEvent

  data class GoTo(val face: GlassFace) : GlassEvent

  data object LongPress : GlassEvent

  data object SwipeUp : GlassEvent

  data object Back : GlassEvent

  data object CycleStyle : GlassEvent

  data object ToggleColors : GlassEvent

  data class PickColor(val index: Int) : GlassEvent

  data object Done : GlassEvent
}

data class GlassUi(
    val face: GlassFace = GlassFace.Portal,
    val looks: Map<GlassFace, FaceLook> = emptyMap(),
    val layer: GlassLayer = GlassLayer.Faces,
    val colorsOpen: Boolean = false,
    /** Direction of the last face change, so the transition slides the right way. */
    val direction: Int = 1,
) {
  fun look(f: GlassFace = face): FaceLook = looks[f] ?: FaceLook()

  fun palette(f: GlassFace = face): FacePalette? =
      f.palettes.getOrNull(look(f).color.coerceIn(0, (f.palettes.size - 1).coerceAtLeast(0)))

  fun reduce(e: GlassEvent): GlassUi =
      when (e) {
        is GlassEvent.Swipe ->
            if (layer == GlassLayer.Home) this
            else {
              val all = GlassFace.entries
              copy(
                  face = all[Math.floorMod(face.ordinal + e.step, all.size)],
                  direction = if (e.step >= 0) 1 else -1,
                  colorsOpen = false)
            }
        is GlassEvent.GoTo ->
            if (layer == GlassLayer.Home || e.face == face) this
            else copy(face = e.face, direction = if (e.face > face) 1 else -1, colorsOpen = false)
        GlassEvent.LongPress -> if (layer == GlassLayer.Faces) copy(layer = GlassLayer.Edit) else this
        GlassEvent.SwipeUp -> if (layer == GlassLayer.Faces) copy(layer = GlassLayer.Home) else this
        GlassEvent.Back, GlassEvent.Done ->
            if (layer == GlassLayer.Faces) this else copy(layer = GlassLayer.Faces, colorsOpen = false)
        GlassEvent.CycleStyle ->
            if (layer != GlassLayer.Edit || face.styles < 2) this
            else withLook(look().copy(style = (look().style + 1) % face.styles))
        GlassEvent.ToggleColors ->
            if (layer != GlassLayer.Edit || face.palettes.size < 2) this
            else copy(colorsOpen = !colorsOpen)
        is GlassEvent.PickColor ->
            if (layer != GlassLayer.Edit || e.index !in face.palettes.indices) this
            else withLook(look().copy(color = e.index))
      }

  private fun withLook(l: FaceLook) = copy(looks = looks + (face to l))
}

/** Persisted form of the face choice: "Portal|Numerals:2:1,Modular:0:0". Unknown parts are dropped. */
object GlassPrefsCodec {
  fun encode(face: GlassFace, looks: Map<GlassFace, FaceLook>): String =
      face.name + "|" + looks.entries.joinToString(",") { (f, l) -> "${f.name}:${l.color}:${l.style}" }

  fun decode(raw: String?): GlassUi {
    if (raw.isNullOrBlank()) return GlassUi()
    val face = GlassFace.entries.firstOrNull { it.name == raw.substringBefore('|') } ?: GlassFace.Portal
    val looks =
        raw.substringAfter('|', "")
            .split(',')
            .mapNotNull { part ->
              val bits = part.split(':')
              val f = GlassFace.entries.firstOrNull { it.name == bits.getOrNull(0) } ?: return@mapNotNull null
              val color = bits.getOrNull(1)?.toIntOrNull()?.takeIf { it in f.palettes.indices } ?: 0
              val style = bits.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0 until f.styles } ?: 0
              f to FaceLook(color, style)
            }
            .toMap()
    return GlassUi(face = face, looks = looks)
  }
}

// --- the Portal face: geometry --------------------------------------------------------------

enum class PortalSlot { Photo, Muse, Clock, Volume, Dnd, Screen, Brightness }

/** Four columns, two rows; Volume fills the last column of both rows. */
fun portalLayout(screen: Size, padding: Float = 40f, gap: Float = 24f): Map<PortalSlot, Rect> {
  val w = (screen.width - 2 * padding - 3 * gap) / 4
  val h = (screen.height - 2 * padding - gap) / 2
  fun cell(col: Int, row: Int, rows: Int = 1): Rect {
    val left = padding + col * (w + gap)
    val top = padding + row * (h + gap)
    return Rect(left, top, left + w, top + rows * h + (rows - 1) * gap)
  }
  return mapOf(
      PortalSlot.Photo to cell(0, 0),
      PortalSlot.Muse to cell(1, 0),
      PortalSlot.Clock to cell(2, 0),
      PortalSlot.Volume to cell(3, 0, rows = 2),
      PortalSlot.Dnd to cell(0, 1),
      PortalSlot.Screen to cell(1, 1),
      PortalSlot.Brightness to cell(2, 1),
  )
}

/**
 * Maps a backdrop bitmap (1/[downscale] of screen size) into a tile's local coordinates while the
 * tile is drawn scaled by [scale] about its centre: local = bitmap * [factor] + ([dx], [dy]).
 * With [scale] equal to the tile's press scale the glass lines up with the page behind it. A larger
 * [scale] pulls backdrop from beyond the tile in towards its centre, which is how the lens rings bend
 * the edges that cross the rim.
 */
data class BackdropTransform(val factor: Float, val dx: Float, val dy: Float)

fun backdropTransform(tile: Rect, scale: Float, downscale: Int): BackdropTransform {
  val c = tile.center
  return BackdropTransform(
      factor = downscale / scale,
      dx = tile.width / 2 - c.x / scale,
      dy = tile.height / 2 - c.y / scale,
  )
}

/** The rect of the backdrop bitmap that a tile at [tile] samples at [scale]. */
fun backdropSourceRect(tile: Rect, scale: Float, downscale: Int): Rect {
  val c = tile.center
  val hw = tile.width * scale / 2
  val hh = tile.height * scale / 2
  return Rect((c.x - hw) / downscale, (c.y - hh) / downscale, (c.x + hw) / downscale, (c.y + hh) / downscale)
}

// --- the Portal face: tile states -----------------------------------------------------------

data class VolumeLevel(val level: Int, val max: Int) {
  val percent: Int
    get() = if (max <= 0) 0 else (level * 100f / max).roundToInt()
}

enum class Dnd { On, Off, NoAccess }

sealed interface Brightness {
  data object Auto : Brightness

  data class Manual(val percent: Int) : Brightness

  data object NoAccess : Brightness
}

fun brightnessPercent(raw: Int): Int = (raw.coerceIn(0, 255) * 100f / 255).roundToInt()

fun brightnessRaw(percent: Int): Int = (percent.coerceIn(0, 100) * 255f / 100).roundToInt()

/** A tap walks Auto → 25 → 50 → 75 → 100% → Auto. Manual levels between steps go to the next step. */
fun nextBrightness(current: Brightness): Brightness =
    when (current) {
      Brightness.NoAccess -> Brightness.NoAccess
      Brightness.Auto -> Brightness.Manual(25)
      is Brightness.Manual ->
          if (current.percent >= 100) Brightness.Auto else Brightness.Manual((current.percent / 25 + 1) * 25)
    }

enum class Glyph { Photo, Moon, Screen, Sun }

data class Tile(val title: String, val status: String, val glyph: Glyph, val on: Boolean = false, val enabled: Boolean = true)

fun dndTile(dnd: Dnd) =
    when (dnd) {
      Dnd.On -> Tile("Do Not Disturb", "On", Glyph.Moon, on = true)
      Dnd.Off -> Tile("Do Not Disturb", "Off", Glyph.Moon)
      Dnd.NoAccess -> Tile("Do Not Disturb", "Needs permission", Glyph.Moon, enabled = false)
    }

fun screenTile(keepAwake: Boolean) =
    if (keepAwake) Tile("Screen", "Keeps awake", Glyph.Screen, on = true)
    else Tile("Screen", "Sleeps when idle", Glyph.Screen)

fun brightnessTile(b: Brightness) =
    when (b) {
      Brightness.Auto -> Tile("Brightness", "Auto", Glyph.Sun, on = true)
      is Brightness.Manual -> Tile("Brightness", "${b.percent}%", Glyph.Sun)
      Brightness.NoAccess -> Tile("Brightness", "Needs permission", Glyph.Sun, enabled = false)
    }

val photoFrameTile = Tile("Photo Frame", "Start slideshow", Glyph.Photo)

/** The few words a weather code reads as on the faces (WMO codes, as Open-Meteo reports them). */
fun weatherLabel(code: Int): String =
    when (code) {
      0 -> "Clear"
      1 -> "Mostly Clear"
      2 -> "Partly Cloudy"
      3 -> "Cloudy"
      in 45..48 -> "Fog"
      in 51..57 -> "Drizzle"
      in 61..67 -> "Rain"
      in 71..77 -> "Snow"
      in 80..82 -> "Showers"
      in 85..86 -> "Snow Showers"
      in 95..99 -> "Thunderstorms"
      else -> ""
    }

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassModelTest {
  private fun GlassUi.run(vararg events: GlassEvent) = events.fold(this) { s, e -> s.reduce(e) }

  @Test
  fun swipesWalkTheCarouselAndWrapAround() {
    val start = GlassUi(face = GlassFace.Numerals)
    assertEquals(GlassFace.Modular, start.run(GlassEvent.Swipe(1)).face)
    val back = start.run(GlassEvent.Swipe(-1))
    assertEquals(GlassFace.Photo, back.face)
    assertEquals(-1, back.direction)
    assertEquals(GlassFace.Numerals, start.run(*Array(GlassFace.entries.size) { GlassEvent.Swipe(1) }).face)
  }

  @Test
  fun homeScreenIgnoresFaceSwipesUntilItIsClosed() {
    val home = GlassUi(face = GlassFace.Portal).run(GlassEvent.SwipeUp)
    assertEquals(GlassLayer.Home, home.layer)
    assertSame(home, home.reduce(GlassEvent.Swipe(1)))
    assertSame(home, home.reduce(GlassEvent.LongPress))
    val faces = home.run(GlassEvent.Back)
    assertEquals(GlassLayer.Faces, faces.layer)
    assertEquals(GlassFace.Portal, faces.face)
  }

  @Test
  fun editModeSwipesFacesAndDoneReturnsToTheCarousel() {
    val edit = GlassUi(face = GlassFace.Portal).run(GlassEvent.LongPress, GlassEvent.Swipe(-1))
    assertEquals(GlassLayer.Edit, edit.layer)
    assertEquals(GlassFace.Modular, edit.face)
    assertSame(edit, edit.reduce(GlassEvent.SwipeUp))
    val done = edit.run(GlassEvent.ToggleColors, GlassEvent.Done)
    assertEquals(GlassLayer.Faces, done.layer)
    assertFalse(done.colorsOpen)
  }

  @Test
  fun colorAndStyleChangesStickToTheFaceBeingEdited() {
    val s =
        GlassUi(face = GlassFace.Numerals)
            .run(
                GlassEvent.LongPress,
                GlassEvent.ToggleColors,
                GlassEvent.PickColor(2),
                GlassEvent.CycleStyle,
                GlassEvent.Swipe(1),
                GlassEvent.PickColor(4))
    assertEquals(FaceLook(color = 2, style = 1), s.look(GlassFace.Numerals))
    assertEquals(FaceLook(color = 4, style = 0), s.look(GlassFace.Modular))
    assertEquals(GlassFace.Modular.palettes[4], s.palette())
    assertEquals(FaceLook(2, 0), s.run(GlassEvent.GoTo(GlassFace.Numerals), GlassEvent.CycleStyle).look(GlassFace.Numerals))
  }

  @Test
  fun editsOutsideEditModeAndOutOfRangeChoicesAreIgnored() {
    val faces = GlassUi(face = GlassFace.Numerals)
    assertSame(faces, faces.reduce(GlassEvent.PickColor(1)))
    assertSame(faces, faces.reduce(GlassEvent.CycleStyle))
    val edit = faces.reduce(GlassEvent.LongPress)
    assertSame(edit, edit.reduce(GlassEvent.PickColor(99)))
    val photo = edit.reduce(GlassEvent.GoTo(GlassFace.Photo))
    assertSame(photo, photo.reduce(GlassEvent.ToggleColors))
    assertSame(photo, photo.reduce(GlassEvent.CycleStyle))
    assertFalse(GlassFace.Photo.editable)
    assertTrue(GlassFace.Numerals.editable)
  }

  @Test
  fun prefsRoundTripAndForgiveBadInput() {
    val ui =
        GlassUi(
            face = GlassFace.Modular,
            looks = mapOf(GlassFace.Numerals to FaceLook(3, 1), GlassFace.Portal to FaceLook(2, 0)))
    val back = GlassPrefsCodec.decode(GlassPrefsCodec.encode(ui.face, ui.looks))
    assertEquals(ui.face, back.face)
    assertEquals(ui.looks, back.looks)
    assertEquals(GlassUi(), GlassPrefsCodec.decode(null))
    val junk = GlassPrefsCodec.decode("Nope|Numerals:99:7,Bogus:1:1,Modular:x")
    assertEquals(GlassFace.Portal, junk.face)
    assertEquals(mapOf(GlassFace.Numerals to FaceLook(0, 0), GlassFace.Modular to FaceLook(0, 0)), junk.looks)
  }

  @Test
  fun portalGridFillsTheScreenWithVolumeAsTheTallTile() {
    val layout = portalLayout(Size(1280f, 800f))
    assertEquals(Rect(40f, 40f, 322f, 388f), layout[PortalSlot.Photo])
    assertEquals(Rect(346f, 40f, 628f, 388f), layout[PortalSlot.Muse])
    assertEquals(Rect(652f, 412f, 934f, 760f), layout[PortalSlot.Brightness])
    assertEquals(Rect(958f, 40f, 1240f, 760f), layout[PortalSlot.Volume])
  }

  @Test
  fun backdropTransformPutsTheTileCentreUnderItselfAtAnyScale() {
    val tile = Rect(346f, 40f, 628f, 388f)
    for (scale in listOf(1f, 0.96f, 1.2f)) {
      val t = backdropTransform(tile, scale, downscale = 4)
      val bitmapCentre = tile.center / 4f
      val local = Offset(bitmapCentre.x * t.factor + t.dx, bitmapCentre.y * t.factor + t.dy)
      assertEquals(tile.width / 2, local.x, 0.01f)
      assertEquals(tile.height / 2, local.y, 0.01f)
    }
    val atRest = backdropTransform(tile, 1f, downscale = 4)
    assertEquals(tile.left / 4 * atRest.factor + atRest.dx, 0f, 0.01f)
  }

  @Test
  fun sourceRectShrinksWithPressAndGrowsWithLensScale() {
    val tile = Rect(40f, 40f, 322f, 388f)
    assertEquals(Rect(10f, 10f, 80.5f, 97f), backdropSourceRect(tile, 1f, 4))
    assertTrue(backdropSourceRect(tile, 0.96f, 4).width < backdropSourceRect(tile, 1f, 4).width)
    assertTrue(backdropSourceRect(tile, 1.3f, 4).width > backdropSourceRect(tile, 1f, 4).width)
  }

  @Test
  fun brightnessTapCyclesThroughQuartersAndBackToAuto() {
    val seen = generateSequence<Brightness>(Brightness.Auto) { nextBrightness(it) }.take(6).toList()
    assertEquals(
        listOf(Brightness.Auto, Brightness.Manual(25), Brightness.Manual(50), Brightness.Manual(75), Brightness.Manual(100), Brightness.Auto),
        seen)
    assertEquals(Brightness.Manual(50), nextBrightness(Brightness.Manual(44)))
    assertEquals(Brightness.NoAccess, nextBrightness(Brightness.NoAccess))
    assertEquals(100, brightnessPercent(brightnessRaw(100)))
  }

  @Test
  fun tileStatesReadTheDevice() {
    assertEquals(Tile("Do Not Disturb", "On", Glyph.Moon, on = true), dndTile(Dnd.On))
    assertFalse(dndTile(Dnd.NoAccess).enabled)
    assertEquals("44%", brightnessTile(Brightness.Manual(44)).status)
    assertTrue(brightnessTile(Brightness.Auto).on)
    assertTrue(screenTile(true).on)
    assertEquals(33, VolumeLevel(6, 18).percent)
    assertEquals(0, VolumeLevel(3, 0).percent)
  }

  @Test
  fun weatherCodesReadAsWords() {
    assertEquals("Clear", weatherLabel(0))
    assertEquals("Rain", weatherLabel(63))
    assertEquals("", weatherLabel(-1))
  }

  @Test
  fun stackBlurKeepsFlatColourAndSpreadsADotSymmetrically() {
    val flat = IntArray(20 * 10) { 0xff2357ec.toInt() }
    stackBlur(flat, 20, 10, radius = 4)
    assertTrue(flat.all { it == 0xff2357ec.toInt() })
    val w = 21
    val px = IntArray(w * w) { 0xff000000.toInt() }
    px[10 * w + 10] = 0xffffffff.toInt()
    stackBlur(px, w, w, radius = 3)
    fun red(x: Int, y: Int) = px[y * w + x] shr 16 and 0xff
    assertEquals(red(8, 10), red(12, 10))
    assertEquals(red(10, 8), red(10, 12))
    assertTrue(red(10, 10) > red(11, 10) && red(11, 10) > red(12, 10))
    assertEquals(0, red(14, 10))
  }

  @Test
  fun wallpapersAreExtraPortalChoicesAfterItsColors() {
    val colors = GlassFace.Portal.palettes.size
    val edit = GlassUi(face = GlassFace.Portal).run(GlassEvent.WallpapersFound(4), GlassEvent.LongPress)
    assertEquals(colors + 4, edit.choices())
    val picked = edit.run(GlassEvent.PickColor(colors + 2))
    assertEquals(2, picked.wallpaper())
    assertEquals(null, edit.run(GlassEvent.PickColor(1)).wallpaper())
    assertSame(edit, edit.reduce(GlassEvent.PickColor(colors + 4)))
    assertEquals(GlassFace.Modular.palettes.size, edit.choices(GlassFace.Modular))
  }

  @Test
  fun aSavedWallpaperSurvivesUntilItsFileIsGone() {
    val colors = GlassFace.Portal.palettes.size
    val saved = GlassPrefsCodec.decode("Portal|Portal:${colors + 1}:0")
    assertEquals(null, saved.wallpaper())
    assertEquals(1, saved.reduce(GlassEvent.WallpapersFound(3)).wallpaper())
    assertEquals(null, saved.reduce(GlassEvent.WallpapersFound(1)).wallpaper())
  }
}

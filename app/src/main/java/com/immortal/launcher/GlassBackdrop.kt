/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Paints a face's background at screen size. */
fun interface BackgroundSource {
  fun render(width: Int, height: Int): Bitmap
}

/**
 * Android 9 has no RenderEffect or RuntimeShader, so the glass can't blur the screen live. A face
 * owns its background, so it is blurred once per background change instead:
 * [sharp] is what the face draws (tile drop shadows baked in), [blurred] the light 1/4-scale blur the
 * glass bodies sample, and [lens] the 1/2-scale copy the rim rings sample.
 */
class GlassBackdrop(
    val sharp: ImageBitmap,
    val blurred: ImageBitmap,
    val lens: ImageBitmap,
) {
  val blurDownscale = 4
  val lensDownscale = 2
}

private const val TAG = "ImmortalGlass"

/** Builds every layer of a backdrop; runs off the main thread (about 160 ms on a Gen-1 Portal). */
fun buildGlassBackdrop(source: BackgroundSource, width: Int, height: Int, tiles: Collection<Rect>): GlassBackdrop {
  val t0 = SystemClock.elapsedRealtime()
  val background = source.render(width, height)
  val blurred = stackBlurred(Bitmap.createScaledBitmap(background, width / 4, height / 4, true), 3)
  val lens = stackBlurred(Bitmap.createScaledBitmap(background, width / 2, height / 2, true), 2)
  if (tiles.isNotEmpty()) bakeShadows(background, tiles)
  Log.i(TAG, "backdrop ${width}x$height built in ${SystemClock.elapsedRealtime() - t0} ms")
  return GlassBackdrop(background.asImageBitmap(), blurred.asImageBitmap(), lens.asImageBitmap())
}

private fun bakeShadows(bitmap: Bitmap, tiles: Collection<Rect>) {
  val canvas = Canvas(bitmap)
  val paint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(80, 4, 10, 40)
        maskFilter = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL)
      }
  for (t in tiles) canvas.drawRoundRect(RectF(t.left + 6, t.top + 16, t.right - 6, t.bottom + 18), 40f, 40f, paint)
}

private fun mix(a: Int, b: Int, t: Float): Int {
  fun ch(shift: Int) = (((a shr shift) and 0xff) * (1 - t) + ((b shr shift) and 0xff) * t).toInt() shl shift
  return (0xff shl 24) or ch(16) or ch(8) or ch(0)
}

/**
 * The Portal face's background: a saturated gradient cut by large angled panels with hard edges,
 * light and dark, so the glass above has strong edges to bend.
 */
fun angledPanels(base: Long) = BackgroundSource { w, h ->
  val c = base.toInt()
  val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
  paint.shader =
      RadialGradient(
          w * 0.3f, h * 0.2f, maxOf(w, h) * 1.1f,
          intArrayOf(mix(c, 0xffffffff.toInt(), 0.28f), c, mix(c, 0xff000000.toInt(), 0.55f)),
          floatArrayOf(0f, 0.45f, 1f),
          Shader.TileMode.CLAMP)
  canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

  val shade = mix(c, 0xff000000.toInt(), 0.75f) and 0xffffff
  fun panel(light: Boolean, alpha: Int, vararg pts: Float) {
    val path = Path()
    path.moveTo(pts[0] * w, pts[1] * h)
    for (i in 2 until pts.size step 2) path.lineTo(pts[i] * w, pts[i + 1] * h)
    path.close()
    val rgb = if (light) 0xffffff else shade
    paint.shader =
        LinearGradient(
            pts[0] * w, pts[1] * h, pts[4] * w, pts[5] * h,
            (alpha shl 24) or rgb, ((alpha * 2 / 3) shl 24) or rgb,
            Shader.TileMode.CLAMP)
    canvas.drawPath(path, paint)
  }
  panel(true, 92, -0.05f, 0.58f, 0.52f, -0.05f, 0.74f, -0.05f, 0.12f, 1.05f, -0.05f, 1.05f)
  panel(false, 110, 0.56f, -0.05f, 1.05f, -0.05f, 1.05f, 0.36f, 0.38f, 1.05f, 0.16f, 1.05f)
  panel(true, 70, 0.42f, 1.05f, 1.05f, 0.16f, 1.05f, 0.50f, 0.70f, 1.05f)
  panel(true, 60, 0.20f, -0.05f, 0.40f, -0.05f, -0.05f, 0.44f, -0.05f, 0.28f)
  panel(false, 96, 0.64f, 1.05f, 1.05f, 0.62f, 1.05f, 1.05f)
  panel(true, 54, 0.82f, -0.05f, 0.96f, -0.05f, 0.32f, 1.05f, 0.18f, 1.05f)
  bitmap
}

/** A flat color background. */
fun solidBackground(color: Long) = BackgroundSource { w, h ->
  Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color.toInt()) }
}

/** A photo, centre-cropped to fill the screen. */
fun photoBackground(photo: Bitmap) = BackgroundSource { w, h ->
  val scale = maxOf(w.toFloat() / photo.width, h.toFloat() / photo.height)
  val sw = (w / scale).toInt()
  val sh = (h / scale).toInt()
  val src = android.graphics.Rect((photo.width - sw) / 2, (photo.height - sh) / 2, (photo.width + sw) / 2, (photo.height + sh) / 2)
  val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  Canvas(out).drawBitmap(photo, src, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
  out
}

/** A heavily blurred copy of [screen] at 1/8 scale, for the Home Screen to sit on. */
fun frostOf(screen: Bitmap): ImageBitmap =
    stackBlurred(Bitmap.createScaledBitmap(screen, screen.width / 8, screen.height / 8, true), 6).asImageBitmap()

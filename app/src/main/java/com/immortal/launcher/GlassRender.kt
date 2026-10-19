/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/**
 * Rounded rect with continuous corners: each corner is three cubics that ease into the straight
 * edge over 1.53r instead of meeting it abruptly like a circular arc.
 */
fun continuousRect(size: Size, r: Float, inset: Float = 0f): Path {
  val l = inset
  val t = inset
  val rt = size.width - inset
  val b = size.height - inset
  val k = minOf(r, (rt - l) / 3.06f, (b - t) / 3.06f)
  return Path().apply {
    moveTo(l + 1.52866f * k, t)
    lineTo(rt - 1.52866f * k, t)
    cubicTo(rt - 1.08849f * k, t, rt - 0.86841f * k, t, rt - 0.63149f * k, t + 0.07491f * k)
    cubicTo(rt - 0.37282f * k, t + 0.16906f * k, rt - 0.16906f * k, t + 0.37282f * k, rt - 0.07491f * k, t + 0.63149f * k)
    cubicTo(rt, t + 0.86841f * k, rt, t + 1.08849f * k, rt, t + 1.52866f * k)
    lineTo(rt, b - 1.52866f * k)
    cubicTo(rt, b - 1.08849f * k, rt, b - 0.86841f * k, rt - 0.07491f * k, b - 0.63149f * k)
    cubicTo(rt - 0.16906f * k, b - 0.37282f * k, rt - 0.37282f * k, b - 0.16906f * k, rt - 0.63149f * k, b - 0.07491f * k)
    cubicTo(rt - 0.86841f * k, b, rt - 1.08849f * k, b, rt - 1.52866f * k, b)
    lineTo(l + 1.52866f * k, b)
    cubicTo(l + 1.08849f * k, b, l + 0.86841f * k, b, l + 0.63149f * k, b - 0.07491f * k)
    cubicTo(l + 0.37282f * k, b - 0.16906f * k, l + 0.16906f * k, b - 0.37282f * k, l + 0.07491f * k, b - 0.63149f * k)
    cubicTo(l, b - 0.86841f * k, l, b - 1.08849f * k, l, b - 1.52866f * k)
    lineTo(l, t + 1.52866f * k)
    cubicTo(l, t + 1.08849f * k, l, t + 0.86841f * k, l + 0.07491f * k, t + 0.63149f * k)
    cubicTo(l + 0.16906f * k, t + 0.37282f * k, l + 0.37282f * k, t + 0.16906f * k, l + 0.63149f * k, t + 0.07491f * k)
    cubicTo(l + 0.86841f * k, t, l + 1.08849f * k, t, l + 1.52866f * k, t)
    close()
  }
}

/** A stadium when [r] reaches half the short side, else a continuous-corner rect. */
fun glassShape(size: Size, r: Float, inset: Float = 0f): Path {
  val half = minOf(size.width, size.height) / 2
  if (r + inset < half) return continuousRect(size, r, inset)
  val rr = half - inset
  return Path().apply {
    addRoundRect(
        androidx.compose.ui.geometry.RoundRect(inset, inset, size.width - inset, size.height - inset, rr, rr))
  }
}

/** Spring-driven press state, 0 at rest and 1 fully pressed. */
class Press(val progress: Animatable<Float, *>)

@Composable fun rememberPress() = remember { Press(Animatable(0f)) }

/**
 * Springs the element to 0.96 while held and back on release, in its own offscreen layer so a press
 * frame redraws only this element; the rest of the face composites as cached textures.
 */
fun Modifier.pressable(press: Press, enabled: Boolean = true, onTap: () -> Unit): Modifier = composed {
  val scope = rememberCoroutineScope()
  val tap by rememberUpdatedState(onTap)
  graphicsLayer {
        val s = 1f - 0.04f * press.progress.value
        scaleX = s
        scaleY = s
        compositingStrategy = CompositingStrategy.Offscreen
      }
      .then(
          if (!enabled) Modifier
          else
              Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                      scope.launch { press.progress.animateTo(1f, spring(0.5f, 300f)) }
                      tryAwaitRelease()
                      scope.launch { press.progress.animateTo(0f, spring(0.45f, 260f)) }
                    },
                    onTap = { tap() },
                )
              })
}

private class LensRing(val path: Path, val stroke: Stroke, val scale: Float, val alpha: Float)

/**
 * The lens rings along a tile's rim: stroke width, how far beyond the tile the ring samples (as a
 * scale about the tile's centre), and its opacity. Wider rings sample further out, so a backdrop edge
 * crossing the rim visibly bends inwards.
 */
private val LENS_RINGS = listOf(Triple(30f, 1.10f, 0.35f), Triple(18f, 1.22f, 0.55f), Triple(8f, 1.38f, 0.85f))

/**
 * Draws liquid glass behind an element laid out at [tile] (screen px): the blurred backdrop under
 * it in a continuous-corner shape, lens rings sampled from the sharper half-scale copy, then tint,
 * inner shadow, top highlight and a 1 px rim. [on] turns it into near-white glass for an active
 * control. Pressing scales the element, so the sampled region shrinks with it and the glass stays
 * aligned with the page behind.
 */
fun Modifier.glass(
    backdrop: GlassBackdrop,
    tile: Rect,
    press: Press,
    on: Boolean,
    radius: Float = 40f,
): Modifier = drawWithCache {
  val shape = continuousRect(size, radius)
  val rimPath = continuousRect(size, radius, inset = 0.5f)
  val highlightPath = continuousRect(size, radius - 1.5f, inset = 1.5f)
  val glassShader = BitmapShader(backdrop.blurred.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
  val glassBrush = ShaderBrush(glassShader)
  val lensShader = BitmapShader(backdrop.lens.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
  val lensBrush = ShaderBrush(lensShader)
  val rings =
      LENS_RINGS.map { (w, s, a) -> LensRing(continuousRect(size, radius - w / 2, inset = w / 2), Stroke(w), s, a) }
  val matrix = Matrix()
  val tint =
      Brush.linearGradient(
          0f to Color.White.copy(alpha = 0.24f),
          0.5f to Color.White.copy(alpha = 0.06f),
          1f to Color.White.copy(alpha = 0.12f),
          start = Offset.Zero,
          end = Offset(size.width, size.height))
  val onTint =
      Brush.linearGradient(
          0f to Color(0xEBFFFFFF), 1f to Color(0xCCE4EBFF), start = Offset.Zero, end = Offset(size.width, size.height))
  val innerShadow = Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0x2A00104A), startY = 0f, endY = size.height)
  val topHighlight =
      Brush.verticalGradient(
          0f to Color.White.copy(alpha = 0.8f), 1f to Color.Transparent, startY = 0f, endY = minOf(size.height * 0.25f, 60f))
  val rim =
      Brush.linearGradient(
          0f to Color.White.copy(alpha = 0.66f),
          0.45f to Color.White.copy(alpha = 0.2f),
          1f to Color.White.copy(alpha = 0.42f),
          start = Offset.Zero,
          end = Offset(size.width, size.height))

  fun DrawScope.sample(shader: Shader, scale: Float, downscale: Int) {
    val t = backdropTransform(tile, scale, downscale)
    matrix.setScale(t.factor, t.factor)
    matrix.postTranslate(t.dx, t.dy)
    shader.setLocalMatrix(matrix)
  }

  onDrawBehind {
    val p = press.progress.value
    val scale = 1f - 0.04f * p
    sample(glassShader, scale, backdrop.blurDownscale)
    drawPath(shape, glassBrush)
    if (!on) {
      for (ring in rings) {
        sample(lensShader, scale * ring.scale, backdrop.lensDownscale)
        drawPath(ring.path, lensBrush, alpha = ring.alpha, style = ring.stroke)
      }
    }
    drawPath(shape, if (on) onTint else tint)
    drawPath(shape, innerShadow)
    if (p > 0f) drawPath(shape, Color.White.copy(alpha = 0.14f * p))
    drawPath(highlightPath, topHighlight, style = Stroke(1.5f))
    drawPath(rimPath, rim, style = Stroke(1f))
  }
}

/**
 * Glass for elements over an already-frosted backdrop (the Home Screen, the edit rail): there is
 * nothing sharp underneath to refract, so this is the tint, highlight and rim without sampling.
 */
fun Modifier.frosted(press: Press? = null, on: Boolean = false, radius: Float = 34f, fill: Color? = null): Modifier =
    drawWithCache {
      val shape = glassShape(size, radius)
      val rimPath = glassShape(size, radius, inset = 0.5f)
      val highlightPath = glassShape(size, radius - 1.5f, inset = 1.5f)
      val body =
          if (fill != null) Brush.linearGradient(listOf(fill, fill.copy(alpha = fill.alpha * 0.8f)), Offset.Zero, Offset(size.width, size.height))
          else if (on) Brush.linearGradient(listOf(Color(0xF2FFFFFF), Color(0xD9E6ECFF)), Offset.Zero, Offset(size.width, size.height))
          else
              Brush.linearGradient(
                  0f to Color.White.copy(alpha = 0.26f),
                  0.55f to Color.White.copy(alpha = 0.10f),
                  1f to Color.White.copy(alpha = 0.16f),
                  start = Offset.Zero,
                  end = Offset(size.width, size.height))
      val topHighlight =
          Brush.verticalGradient(0f to Color.White.copy(alpha = 0.7f), 1f to Color.Transparent, startY = 0f, endY = minOf(size.height * 0.3f, 40f))
      val rim =
          Brush.linearGradient(
              0f to Color.White.copy(alpha = 0.6f),
              0.45f to Color.White.copy(alpha = 0.18f),
              1f to Color.White.copy(alpha = 0.38f),
              start = Offset.Zero,
              end = Offset(size.width, size.height))
      onDrawBehind {
        drawPath(shape, body)
        val p = press?.progress?.value ?: 0f
        if (p > 0f) drawPath(shape, Color.White.copy(alpha = 0.16f * p))
        drawPath(highlightPath, topHighlight, style = Stroke(1.5f))
        drawPath(rimPath, rim, style = Stroke(1f))
      }
    }

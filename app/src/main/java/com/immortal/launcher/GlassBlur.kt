/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.Bitmap
import kotlin.math.abs

/** A blurred copy of [src] (stack blur, [radius] in [src]'s pixels). */
fun stackBlurred(src: Bitmap, radius: Int): Bitmap {
  val w = src.width
  val h = src.height
  val px = IntArray(w * h)
  src.getPixels(px, 0, w, 0, 0, w, h)
  stackBlur(px, w, h, radius)
  return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

/** Mario Klingemann's stack blur, in place: two passes of a triangle-weighted running sum. */
internal fun stackBlur(px: IntArray, w: Int, h: Int, radius: Int) {
  if (radius < 1) return
  val tmp = IntArray(w * h)
  blurPass(px, tmp, w, h, radius, horizontal = true)
  blurPass(tmp, px, w, h, radius, horizontal = false)
}

private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
  val lines = if (horizontal) h else w
  val len = if (horizontal) w else h
  val div = (r + 1) * (r + 1)
  val stack = IntArray(2 * r + 1)
  for (line in 0 until lines) {
    fun at(i: Int) =
        src[if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line]
    var sa = 0
    var sr = 0
    var sg = 0
    var sb = 0
    var ia = 0
    var ir = 0
    var ig = 0
    var ib = 0
    var oa = 0
    var or = 0
    var og = 0
    var ob = 0
    for (i in -r..r) {
      val c = at(i)
      stack[i + r] = c
      val weight = r + 1 - abs(i)
      sa += (c ushr 24) * weight
      sr += (c shr 16 and 0xff) * weight
      sg += (c shr 8 and 0xff) * weight
      sb += (c and 0xff) * weight
      if (i > 0) {
        ia += c ushr 24
        ir += c shr 16 and 0xff
        ig += c shr 8 and 0xff
        ib += c and 0xff
      } else {
        oa += c ushr 24
        or += c shr 16 and 0xff
        og += c shr 8 and 0xff
        ob += c and 0xff
      }
    }
    var sp = r
    for (i in 0 until len) {
      dst[if (horizontal) line * w + i else i * w + line] =
          (sa / div shl 24) or (sr / div shl 16) or (sg / div shl 8) or (sb / div)
      sa -= oa
      sr -= or
      sg -= og
      sb -= ob
      val start = (sp - r + 2 * r + 1) % (2 * r + 1)
      var c = stack[start]
      oa -= c ushr 24
      or -= c shr 16 and 0xff
      og -= c shr 8 and 0xff
      ob -= c and 0xff
      c = at(i + r + 1)
      stack[start] = c
      ia += c ushr 24
      ir += c shr 16 and 0xff
      ig += c shr 8 and 0xff
      ib += c and 0xff
      sa += ia
      sr += ir
      sg += ig
      sb += ib
      sp = (sp + 1) % (2 * r + 1)
      c = stack[sp]
      oa += c ushr 24
      or += c shr 16 and 0xff
      og += c shr 8 and 0xff
      ob += c and 0xff
      ia -= c ushr 24
      ir -= c shr 16 and 0xff
      ig -= c shr 8 and 0xff
      ib -= c and 0xff
    }
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import java.io.File

private const val TAG = "ImmortalGlass"

/** The chosen face and each face's color and style, kept across restarts. */
object GlassPrefs {
  private const val PREFS = "immortal_glass"
  private const val KEY = "faces"

  fun load(context: Context): GlassUi =
      GlassPrefsCodec.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

  fun save(context: Context, ui: GlassUi) =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, GlassPrefsCodec.encode(ui.face, ui.looks)).apply()
}

/** The device controls the Portal face shows, read in one snapshot. */
data class GlassDeviceState(val volume: VolumeLevel, val dnd: Dnd, val brightness: Brightness)

/** Reads and changes the system state behind the Portal face's tiles. */
class GlassDevice(private val context: Context) {
  private val audio = context.getSystemService(AudioManager::class.java)
  private val notifications = context.getSystemService(NotificationManager::class.java)
  private val resolver get() = context.contentResolver

  fun read() = GlassDeviceState(volume(), dnd(), brightness())

  private fun volume() =
      VolumeLevel(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))

  fun stepVolume(delta: Int) =
      audio.adjustStreamVolume(
          AudioManager.STREAM_MUSIC, if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)

  private fun dnd() =
      when {
        !notifications.isNotificationPolicyAccessGranted -> Dnd.NoAccess
        notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL -> Dnd.Off
        else -> Dnd.On
      }

  fun toggleDnd() {
    val current = dnd()
    if (current == Dnd.NoAccess) return
    notifications.setInterruptionFilter(
        if (current == Dnd.Off) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
  }

  private fun brightness(): Brightness {
    if (!Settings.System.canWrite(context)) return Brightness.NoAccess
    val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
    if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) return Brightness.Auto
    return Brightness.Manual(brightnessPercent(Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)))
  }

  fun stepBrightness() {
    when (val next = nextBrightness(brightness())) {
      Brightness.NoAccess -> Unit
      Brightness.Auto ->
          Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
      is Brightness.Manual -> {
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightnessRaw(next.percent))
      }
    }
  }
}

private const val MUSE = "dev.wobsoriano.museportal"

private fun museWake() =
    Intent("com.portal.wake.action.WAKE")
        .setComponent(ComponentName(MUSE, "$MUSE.heymuse.PortalWakeReceiver"))
        .putExtra("com.portal.wake.extra.ID", "muse")

/** Whether the Muse assistant's wake receiver is installed. */
fun museInstalled(context: Context): Boolean =
    runCatching { context.packageManager.queryBroadcastReceivers(museWake(), 0).isNotEmpty() }.getOrDefault(false)

/** Asks Muse to start listening, the same broadcast its own wake word sends. */
fun heyMuse(context: Context) {
  Log.i(TAG, "Hey Muse: portal-wake broadcast")
  context.sendBroadcast(museWake())
}

/** Current conditions plus today's high and low, for the Modular face and the weather widget. */
data class GlassWeather(val city: String, val temp: Int, val code: Int, val hi: Int?, val lo: Int?)

fun fetchGlassWeather(context: Context): GlassWeather? {
  val now = Weather.fetchCurrent(context) ?: return null
  val today = Weather.fetchForecast(context)?.days?.firstOrNull()
  return GlassWeather(now.city, now.temp, now.code, today?.hi, today?.lo)
}

/** One calendar entry for today, from the screensaver's calendar feed or the device calendar. */
data class AgendaItem(val start: Long, val end: Long, val title: String, val allDay: Boolean)

/** Null when no calendar is configured, so the face can say so instead of claiming a free day. */
fun fetchTodayAgenda(context: Context): List<AgendaItem>? {
  val now = System.currentTimeMillis()
  val url = ScreensaverConfig.load(context).calendarUrl
  if (!url.isNullOrBlank()) {
    return CalendarFeed.window(CalendarFeed.fetch(url), CalendarFeed.RANGE_DAY, now).map {
      AgendaItem(it.startMillis, it.endMillis, it.title, it.allDay)
    }
  }
  if (CalendarHelper.hasPermission(context)) {
    val dayEnd =
        java.util.Calendar.getInstance()
            .apply {
              timeInMillis = now
              set(java.util.Calendar.HOUR_OF_DAY, 23)
              set(java.util.Calendar.MINUTE, 59)
            }
            .timeInMillis
    return CalendarHelper.upcoming(context, 1).filter { it.begin <= dayEnd }.map { AgendaItem(it.begin, it.end, it.title, it.allDay) }
  }
  return null
}

/**
 * A photo for the Photo face from the frame's own sources, without the network: the configured
 * folder, else the frame's media cache, else the bundled fallback photos.
 */
fun pickGlassPhoto(context: Context): Bitmap? {
  val settings = ScreensaverConfig.load(context)
  val candidates = buildList {
    if (settings.usesFolder) {
      LocalMedia.enumerate(settings.folderPath!!, includeVideo = false, max = 200).randomOrNull()?.let { add(File(it.path)) }
    }
    File(context.filesDir, MediaCache.DIR)
        .listFiles { f -> f.name.endsWith(".jpg") }
        ?.maxByOrNull { it.lastModified() }
        ?.let { add(it) }
  }
  for (f in candidates) decodeBounded(f.readBytesOrNull())?.let { return it }
  val bundled = runCatching { context.assets.list("photoframe_fallback")?.filter { it.endsWith(".jpg") } }.getOrNull()
  val name = bundled?.randomOrNull() ?: return null
  return runCatching { context.assets.open("photoframe_fallback/$name").use { decodeBounded(it.readBytes()) } }.getOrNull()
}

private fun File.readBytesOrNull() = runCatching { readBytes() }.getOrNull()

private fun decodeBounded(bytes: ByteArray?, maxEdge: Int = 1600): Bitmap? {
  if (bytes == null) return null
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
  return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

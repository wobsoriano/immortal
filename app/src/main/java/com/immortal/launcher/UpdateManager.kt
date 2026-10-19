/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

/** A newer build advertised by the remote manifest. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
)

const val UPDATE_INSTALL_ACTION = "com.immortal.launcher.UPDATE_INSTALL_STATUS"

/**
 * Over-the-air self-update. Immortal fetches a hosted `version.json`; if it
 * advertises a higher `versionCode` than what's installed, it downloads the APK
 * and installs it over itself via [PackageInstaller] — no laptop, no ADB. Works
 * because the provisioning step disables Meta's verifier, and an in-place update
 * is signature-checked against the running app (so ship every build with the
 * same signing key).
 */
object UpdateManager {
  // Point this at your hosted manifest (e.g. a GitHub raw URL). Overridable so
  // the same field can target a test server on the LAN.
  @Volatile
  var updateUrl: String =
      "https://raw.githubusercontent.com/wobsoriano/immortal-glass/glass/version.json"

  private val io = Executors.newSingleThreadExecutor()
  // Lazy so touching the pure helpers (parseManifest/shouldUpdate/cacheBust) in a
  // unit test doesn't eagerly construct a main-looper Handler (unavailable off-device).
  private val main by lazy { Handler(Looper.getMainLooper()) }

  /** Returns an [UpdateInfo] on the main thread only if a newer build exists. */
  fun checkForUpdate(context: Context, onResult: (UpdateInfo?) -> Unit) {
    // Dev mode pauses the official self-updater so a locally-pushed build sticks.
    if (DevMode.isEnabled(context)) {
      android.util.Log.i("ImmortalUpdate", "dev mode on; skipping official update check")
      onResult(null)
      return
    }
    io.execute {
      val available =
          runCatching {
                // Cache-bust: GitHub raw caches ~5 min, which would delay update
                // detection; a unique query param forces a fresh read each check.
                val url = cacheBust(resolveUrl(context), System.currentTimeMillis())
                val info = parseManifest(httpGet(url))
                android.util.Log.i(
                    "ImmortalUpdate",
                    "url=$url remote=${info.versionCode} installed=${installedVersionCode(context)}",
                )
                if (shouldUpdate(info.versionCode, installedVersionCode(context))) info else null
              }
              .onFailure { android.util.Log.w("ImmortalUpdate", "update check failed", it) }
              .getOrNull()
      main.post { onResult(available) }
    }
  }

  /** Parse a `version.json` manifest (extracted as a pure function for testing). */
  internal fun parseManifest(json: String): UpdateInfo {
    val j = JSONObject(json)
    return UpdateInfo(
        versionCode = j.getLong("versionCode"),
        versionName = j.optString("versionName"),
        apkUrl = j.getString("apkUrl"),
        notes = j.optString("notes"),
    )
  }

  /** Whether the remote build is newer than what's installed. */
  internal fun shouldUpdate(remoteVersionCode: Long, installedVersionCode: Long): Boolean =
      remoteVersionCode > installedVersionCode

  /** Append a cache-busting query param, preserving any existing query string. */
  internal fun cacheBust(base: String, t: Long): String =
      base + (if (base.contains("?")) "&" else "?") + "t=" + t

  /** Downloads and commits the update; status text is posted on the main thread. */
  fun installUpdate(context: Context, info: UpdateInfo, status: (String) -> Unit) {
    if (InstallDaemon.installPaused(context)) {
      // Gen-1 with the daemon down — the broken system installer can't apply it.
      status("Paused — connect to your computer to update")
      return
    }
    status("Downloading update…")
    io.execute {
      try {
        val apk = File(context.cacheDir, "immortal-update.apk")
        download(info.apkUrl, apk)
        main.post { status("Installing…") }
        if (InstallDaemon.isAvailable(context)) {
          // Silent self-update via the provisioning daemon (also the only path
          // that works on the Gen-1 Portal+, whose installer dialog is broken).
          val ok = InstallDaemon.install(context, apk, "immortal-update")
          main.post { status(if (ok) "Updated" else "Update failed") }
        } else {
          PackageInstallSessions.commit(context, apk, UPDATE_INSTALL_ACTION)
        }
      } catch (t: Throwable) {
        main.post { status("Update failed: ${t.message ?: t.javaClass.simpleName}") }
      }
    }
  }

  // Lets a pushed file override the manifest URL (handy for testing against a
  // LAN server without rebuilding). Falls back to [updateUrl].
  private fun resolveUrl(context: Context): String {
    val f = File(context.getExternalFilesDir(null), "update_url.txt")
    return if (f.exists()) runCatching { f.readText().trim() }.getOrDefault(updateUrl) else updateUrl
  }

  fun installedVersionCode(context: Context): Long =
      runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
          }
          .getOrDefault(0L)

  private fun httpGet(spec: String): String = open(spec).inputStream.use {
    it.readBytes().toString(Charsets.UTF_8)
  }

  private fun download(spec: String, dest: File) {
    open(spec).inputStream.use { input ->
      java.io.FileOutputStream(dest).use { input.copyTo(it) }
    }
  }

  private fun open(spec: String): HttpURLConnection {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 10000
    c.readTimeout = 30000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "Immortal/1.0")
    return c
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.PixelCopy
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext

/** What the Glass home hands back to [HomeActivity]: the same launches the original grid makes. */
class GlassActions(
    val launch: (ComponentName) -> Unit,
    val openStore: () -> Unit,
    val openHelp: () -> Unit,
    val photoFrame: () -> Unit,
    val calls: () -> Unit,
)

private val EditBackdrop = Brush.radialGradient(listOf(Color(0xFF1B2030), Color(0xFF07080C)), Offset(640f, 0f), 1100f)

/**
 * The Glass theme's home: a carousel of faces, long-press to edit them, and a personal Home
 * Screen one swipe up. A separate tree from the original grid; [HomeActivity] picks one.
 */
@Composable
fun GlassHome(actions: GlassActions) {
  val context = LocalContext.current
  val view = LocalView.current
  var ui by remember { mutableStateOf(GlassPrefs.load(context)) }
  val scope = rememberCoroutineScope()
  var homeFrost by remember { mutableStateOf<ImageBitmap?>(null) }
  // Face transitions: the face being left, and how far the new one has slid in (0 to 1).
  var leaving by remember { mutableStateOf<GlassFace?>(null) }
  val slide = remember { Animatable(1f) }
  fun send(e: GlassEvent) {
    if (e == GlassEvent.SwipeUp && ui.layer == GlassLayer.Faces) {
      // The Home Screen sits on a blur of whatever the face shows right now, hands and tiles included.
      scope.launch {
        homeFrost = (context as? Activity)?.window?.let { captureFrost(it, view.width, view.height) }
        ui = ui.reduce(e)
      }
      return
    }
    val next = ui.reduce(e)
    if (next.face != ui.face) {
      leaving = ui.face
      // Undispatched, so the slide restarts from 0 in the same frame the new face is chosen.
      scope.launch(start = CoroutineStart.UNDISPATCHED) {
        slide.snapTo(0f)
        slide.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
        leaving = null
      }
    }
    ui = next
  }
  LaunchedEffect(Unit) { snapshotFlow { ui.face to ui.looks }.collect { GlassPrefs.save(context, ui) } }
  // Composition reads [ui] only through these, so a swipe, which changes just the face, recomposes
  // nothing but the page dots. Recomposing this whole screen cost each swipe's first frame 5-10 ms.
  val layer by remember { derivedStateOf { ui.layer } }
  val portalColor by remember { derivedStateOf { ui.palette(GlassFace.Portal)!!.background } }
  val portalWallpaperIndex by remember { derivedStateOf { ui.wallpaper(GlassFace.Portal) } }
  // Always on: as the home screen, Back on a face must not finish us and expose whatever task is below.
  BackHandler { send(GlassEvent.Back) }

  val device = remember { GlassDevice(context) }
  var deviceState by remember { mutableStateOf(device.read()) }
  var keepAwake by remember { mutableStateOf(false) }
  var museReady by remember { mutableStateOf(museInstalled(context)) }
  var use24Hour by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  var heyPkg by remember { mutableStateOf(heyPackage(context)) }
  DisposableEffect(keepAwake) {
    view.keepScreenOn = keepAwake
    onDispose { view.keepScreenOn = false }
  }
  // Device controls are binder calls (and DND writes its config to disk), so they run off the main
  // thread; on it they held frames up by 20-40 ms on a Gen-1 Portal.
  fun control(change: () -> Unit) {
    scope.launch {
      deviceState = withContext(Dispatchers.IO) {
        change()
        device.read()
      }
    }
  }
  DeviceWatcher { control {} }
  var weatherPlace by remember { mutableStateOf(Weather.locationLabel(context)) }
  var wallpaperFiles by remember { mutableStateOf(glassWallpapers(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) {
        control {}
        museReady = museInstalled(context)
        use24Hour = ImmortalSettings.use24HourClock(context)
        heyPkg = heyPackage(context)
        weatherPlace = Weather.locationLabel(context)
        wallpaperFiles = glassWallpapers(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  val weather by
      produceState<GlassWeather?>(null, weatherPlace) {
        while (true) {
          val w = withContext(Dispatchers.IO) { fetchGlassWeather(context) }
          if (w != null) value = w
          delay(if (w != null) 30L * 60 * 1000 else 60L * 1000)
        }
      }
  val agenda by
      produceState<List<AgendaItem>?>(null) {
        while (true) {
          value = withContext(Dispatchers.IO) { fetchTodayAgenda(context) }
          delay(15L * 60 * 1000)
        }
      }
  val photo by
      produceState<android.graphics.Bitmap?>(null) {
        while (true) {
          withContext(Dispatchers.IO) { pickGlassPhoto(context) }?.let { value = it }
          delay(30L * 60 * 1000)
        }
      }
  val wallpapers by
      produceState(emptyList<Wallpaper>(), wallpaperFiles) {
        value = withContext(Dispatchers.IO) { wallpaperFiles.mapNotNull { f -> loadWallpaper(f, 96)?.let { Wallpaper(f, it.asImageBitmap()) } } }
      }
  LaunchedEffect(wallpapers.size) { send(GlassEvent.WallpapersFound(wallpapers.size)) }
  val selfUpdate = rememberSelfUpdate()
  var timerRinging by remember { mutableStateOf(TimerStore.load(context).ringing) }
  DisposableEffect(Unit) {
    val l: () -> Unit = { timerRinging = TimerStore.load(context).ringing }
    TimerStore.addListener(l)
    onDispose { TimerStore.removeListener(l) }
  }

  // Remembered so a face change doesn't hand the Portal face new actions and recompose its tiles.
  val portalActions =
      remember(actions) {
        PortalActions(
            photoFrame = actions.photoFrame,
            heyMuse = { heyMuse(context) },
            volume = { delta -> control { device.stepVolume(delta) } },
            dnd = { control { device.toggleDnd() } },
            screen = { keepAwake = !keepAwake },
            brightness = { control { device.stepBrightness() } },
        )
      }

  BoxWithConstraints(Modifier.fillMaxSize().background(EditBackdrop)) {
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val portalLayout = remember(w, h) { portalLayout(Size(w.toFloat(), h.toFloat() - CHROME_HEIGHT)) }
    val portalWallpaper = portalWallpaperIndex?.let { wallpapers.getOrNull(it)?.file }
    val portalBackdrop by
        produceState<GlassBackdrop?>(null, w, h, portalColor, portalWallpaper) {
          value =
              withContext(Dispatchers.Default) {
                val image = portalWallpaper?.let { loadWallpaper(it) }
                val source = if (image != null) photoBackground(image) else angledPanels(portalColor)
                buildGlassBackdrop(source, w, h, portalLayout.values)
              }
        }
    val photoBackdrop by
        produceState<GlassBackdrop?>(null, w, h, photo) {
          val p = photo ?: return@produceState
          value = withContext(Dispatchers.Default) { buildGlassBackdrop(photoBackground(p), w, h, emptyList()) }
        }

    // Remembered so a face change doesn't rebuild the gesture detector.
    val gestures = remember { Modifier.faceGestures(editing = { ui.layer == GlassLayer.Edit }, onEvent = ::send) }
    val edit by animateFloatAsState(if (layer == GlassLayer.Edit) 1f else 0f, tween(450, easing = FastOutSlowInEasing), label = "edit")
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
              val s = 1f - 0.32f * edit
              scaleX = s
              scaleY = s
              translationX = -140f * edit
              if (edit > 0f) {
                clip = true
                shape = RoundedCornerShape(with(density) { (106 * edit).dp })
              }
            }
            .then(gestures)) {
          // Every face stays composed and in the render tree; the ones not on screen are parked
          // beside it. A swipe then only moves two layers. Composing a face as it slid in cost a
          // 60-120 ms first frame on the Gen-1 Portal, and the double-buffered pipeline stayed a
          // frame behind for the rest of the slide. The incoming face slides over the outgoing one,
          // which drifts back at a quarter of the speed. No fades: an alpha transition renders both
          // faces offscreen, and that fill cost made a third of the frames janky.
          // Which face is where is read only while placing and drawing, so a swipe recomposes
          // nothing here: the incoming face is placed on top and the two layers move.
          Layout(
              content = {
                GlassFace.entries.forEach { face ->
                  key(face) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                          translationX =
                              when (face) {
                                ui.face -> ui.direction * size.width * (1f - slide.value)
                                leaving -> -ui.direction * size.width / 4f * slide.value
                                else -> PARKED * size.width
                              }
                        }) {
                          when (face) {
                            GlassFace.Portal -> PortalFace(portalBackdrop, portalLayout, deviceState, keepAwake, museReady, portalActions)
                            GlassFace.Numerals -> {
                              val look by remember { derivedStateOf { ui.palette(face)!! to ui.look(face).style } }
                              NumeralsFace(look.first, look.second)
                            }
                            GlassFace.Modular -> {
                              val look by remember { derivedStateOf { ui.palette(face)!! to ui.look(face).style } }
                              ModularFace(look.first, look.second, weather, agenda, use24Hour)
                            }
                            GlassFace.Photo -> PhotoFace(photoBackdrop, use24Hour)
                          }
                        }
                  }
                }
              }) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints) }
                layout(constraints.maxWidth, constraints.maxHeight) {
                  placeables.forEachIndexed { i, p -> p.place(0, 0, zIndex = if (GlassFace.entries[i] == ui.face) 1f else 0f) }
                }
              }
        }

    // Parked rather than removed under the Home Screen and edit mode, so closing those doesn't
    // compose the chrome again mid-slide.
    Box(Modifier.parked(layer != GlassLayer.Faces)) {
      FaceChrome(
          face = { ui.face },
          museReady = museReady,
          onHeyMuse = { heyMuse(context) },
          onHome = { send(GlassEvent.SwipeUp) },
          onDot = { send(GlassEvent.GoTo(it)) })
    }
    if (edit > 0f) EditRail(ui, wallpapers, alpha = edit, onEvent = ::send)

    // The Home Screen stays composed and drawn, parked just below the screen while closed, so
    // opening it only moves a layer. Unplacing it dropped its display lists from the render tree,
    // and re-recording fifteen app tiles cost the first frame of every open about 30 ms.
    val home by animateFloatAsState(if (layer == GlassLayer.Home) 1f else 0f, tween(380, easing = FastOutSlowInEasing), label = "home")
    Box(Modifier.fillMaxSize().graphicsLayer { translationY = (1f - home) * size.height }) {
          GlassHomeScreen(
              frost = homeFrost,
              fallback = { Color(ui.palette()?.background ?: 0xFF1B2030) },
              weather = weather,
              use24Hour = use24Hour,
              heyPkg = heyPkg,
              selfUpdate = selfUpdate,
              actions = actions,
              onClose = { send(GlassEvent.Back) })
        }

    if (timerRinging) TimerAlarmOverlay(onStop = { TimerAlarm.stop(context) })
  }
}

/** A 1/8-scale heavy blur of the window as it is on screen now. */
private suspend fun captureFrost(window: Window, width: Int, height: Int): ImageBitmap? {
  if (width <= 0 || height <= 0) return null
  val shot = Bitmap.createBitmap(width / 8, height / 8, Bitmap.Config.ARGB_8888)
  val copied =
      suspendCancellableCoroutine { cont ->
        PixelCopy.request(window, shot, { cont.resume(it == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
      }
  if (!copied) return null
  return withContext(Dispatchers.Default) { stackBlurred(shot, 6).asImageBitmap() }
}

/** Where a face that isn't showing waits, in screen widths: off screen, so it draws and hit-tests nothing. */
private const val PARKED = 2f

/**
 * Moves an element off screen instead of removing it. It stays composed and keeps its display
 * lists and layers on the render thread, so bringing it back costs a frame no more than any other.
 */
private fun Modifier.parked(parked: Boolean): Modifier =
    fillMaxSize().graphicsLayer { translationX = if (parked) PARKED * size.width else 0f }

/** Space kept under the Portal face's tiles for the page dots and the Home Screen pill. */
private const val CHROME_HEIGHT = 56f

/** Re-reads the device when volume, Do Not Disturb or brightness change outside the face. */
@Composable
private fun DeviceWatcher(onChange: () -> Unit) {
  val context = LocalContext.current
  val latest by rememberUpdatedState(onChange)
  DisposableEffect(Unit) {
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, i: Intent) = latest()
        }
    val filter =
        IntentFilter().apply {
          addAction("android.media.VOLUME_CHANGED_ACTION")
          addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
          addAction(android.app.NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
        }
    if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
          override fun onChange(selfChange: Boolean) = latest()
        }
    for (key in listOf(Settings.System.SCREEN_BRIGHTNESS, Settings.System.SCREEN_BRIGHTNESS_MODE)) {
      context.contentResolver.registerContentObserver(Settings.System.getUriFor(key), false, observer)
    }
    onDispose {
      runCatching { context.unregisterReceiver(receiver) }
      context.contentResolver.unregisterContentObserver(observer)
    }
  }
}

private enum class Gesture { Tap, Drag }

/**
 * Face-level gestures, read in the Initial pass so they win over the tiles underneath: a horizontal
 * swipe changes face, an upward swipe opens the Home Screen, and a long press enters edit mode. A
 * plain tap passes through to the tile. While [editing], every touch is the card's, so tiles under it
 * stay inert.
 */
private fun Modifier.faceGestures(editing: () -> Boolean, onEvent: (GlassEvent) -> Unit): Modifier =
  // [editing] is read per gesture rather than keying pointerInput on it: a long press flips it, and
  // restarting the detector then would drop the rest of that touch, letting its release reach the
  // tile underneath as a tap.
  pointerInput(Unit) {
      awaitEachGesture {
        val editing = editing()
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (editing) down.consume()
        val slop = viewConfiguration.touchSlop
        val outcome =
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
              var result: Gesture? = null
              while (result == null) {
                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                if (c == null || !c.pressed) {
                  result = Gesture.Tap
                } else {
                  if (editing) c.consume()
                  if ((c.position - down.position).getDistance() > slop) result = Gesture.Drag
                }
              }
              result
            }
        when (outcome) {
          null -> {
            if (!editing) onEvent(GlassEvent.LongPress)
            consumeUntilUp(down.id)
          }
          Gesture.Drag -> {
            val end = consumeUntilUp(down.id) ?: return@awaitEachGesture
            val dx = end.x - down.position.x
            val dy = end.y - down.position.y
            when {
              abs(dx) > 70 && abs(dx) > abs(dy) -> onEvent(GlassEvent.Swipe(if (dx < 0) 1 else -1))
              dy < -90 && abs(dy) > abs(dx) -> onEvent(GlassEvent.SwipeUp)
            }
          }
          Gesture.Tap -> Unit
        }
      }
    }

/** Swallows the rest of a gesture so nothing underneath reacts; returns where the finger lifted. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.consumeUntilUp(id: PointerId): Offset? {
  var last: Offset? = null
  while (true) {
    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == id } ?: return last
    c.consume()
    last = c.position
    if (!c.pressed) return last
  }
}

// --- chrome over the faces --------------------------------------------------------------------

/**
 * The page dots, Home Screen pill and (off the Portal face) the Hey Muse pill. [face] is read only
 * while laying out and drawing, so a swipe recomposes and measures nothing here. Recomposing the
 * chrome cost a swipe's first frame 5-20 ms on a Gen-1 Portal, most of it composing and drawing the
 * Muse pill again each time the Portal face came or went.
 */
@Composable
private fun FaceChrome(face: () -> GlassFace, museReady: Boolean, onHeyMuse: () -> Unit, onHome: () -> Unit, onDot: (GlassFace) -> Unit) {
  Box(Modifier.fillMaxSize()) {
    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
      if (museReady) {
        Box(remember { Modifier.collapsedWhen(gap = 20.dp) { face() == GlassFace.Portal } }) { MusePill(onHeyMuse) }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        PageDots(face, onDot)
        HomePill(onHome)
      }
    }
  }
}

/**
 * Lays the element out with [gap] after it, or, while [collapsed], at no size with the element
 * placed far off screen: it stays composed and drawn, so showing it again costs no frame.
 */
private fun Modifier.collapsedWhen(gap: androidx.compose.ui.unit.Dp, collapsed: () -> Boolean): Modifier =
    layout { m, c ->
      val p = m.measure(c)
      if (collapsed()) layout(0, 0) { p.place(-100_000, 0) }
      else layout(p.width + gap.roundToPx(), p.height) { p.place(0, 0) }
    }

@Composable
private fun MusePill(onTap: () -> Unit) {
  val muse = ImageBitmap.imageResource(R.drawable.glass_muse)
  val press = rememberPress()
  Row(
      Modifier.height(44.dp).pressable(press, onTap = onTap).frosted(press, radius = 22f).padding(start = 6.dp, end = 18.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(muse, null, Modifier.size(34.dp), filterQuality = FilterQuality.None)
        BasicText("Hey Muse", style = text(18))
      }
}

private val DOT = 11.dp
private val DOT_SELECTED = 26.dp
private val DOT_GAP = 10.dp

/** Where each dot sits along the row, in px, with [current] drawn wide. */
private fun dotSpans(current: GlassFace, dot: Float, selected: Float, gap: Float): List<Pair<Float, Float>> {
  var x = 0f
  return GlassFace.entries.map { f ->
    val w = if (f == current) selected else dot
    (x to x + w).also { x += w + gap }
  }
}

@Composable
private fun PageDots(face: () -> GlassFace, onDot: (GlassFace) -> Unit) {
  val n = GlassFace.entries.size
  val tap by rememberUpdatedState(onDot)
  // One canvas, read at draw time: a swipe redraws the dots without recomposing or measuring them.
  Box(Modifier.height(44.dp).frosted(radius = 22f).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
    Canvas(
        Modifier.size(width = DOT_SELECTED + DOT * (n - 1) + DOT_GAP * (n - 1), height = DOT).pointerInput(Unit) {
          detectTapGestures { pos ->
            val spans = dotSpans(face(), DOT.toPx(), DOT_SELECTED.toPx(), DOT_GAP.toPx())
            spans.indexOfFirst { (l, r) -> pos.x in l..r }.takeIf { it >= 0 }?.let { tap(GlassFace.entries[it]) }
          }
        }) {
          val current = face()
          val h = size.height
          dotSpans(current, DOT.toPx(), DOT_SELECTED.toPx(), DOT_GAP.toPx()).forEachIndexed { i, (l, r) ->
            drawRoundRect(
                if (GlassFace.entries[i] == current) Color.White else Color.White.copy(alpha = 0.45f),
                topLeft = Offset(l, 0f),
                size = Size(r - l, h),
                cornerRadius = CornerRadius(h / 2))
          }
        }
  }
}

@Composable
private fun HomePill(onTap: () -> Unit) {
  val press = rememberPress()
  Row(
      Modifier.height(44.dp).pressable(press, onTap = onTap).frosted(press, radius = 22f).padding(horizontal = 18.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chevron(up = true, size = 18)
        BasicText("Home Screen", style = text(18))
      }
}

@Composable
private fun Chevron(up: Boolean, size: Int, color: Color = Color.White) {
  Canvas(Modifier.size(size.dp)) {
    val s = this.size.width / 24f
    val y0 = if (up) 15f else 9f
    val y1 = if (up) 9f else 15f
    val path =
        androidx.compose.ui.graphics.Path().apply {
          moveTo(6 * s, y0 * s)
          lineTo(12 * s, y1 * s)
          lineTo(18 * s, y0 * s)
        }
    drawPath(path, color, style = Stroke(2.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
  }
}

// --- edit mode ------------------------------------------------------------------------------

@Composable
private fun EditRail(ui: GlassUi, wallpapers: List<Wallpaper>, alpha: Float, onEvent: (GlassEvent) -> Unit) {
  Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
    BasicText(
        ui.face.label,
        Modifier.width(1000.dp).padding(top = 30.dp),
        style = text(30, align = TextAlign.Center))
    BasicText(
        "Swipe to choose a face · tap Done to keep it",
        Modifier.width(1000.dp).align(Alignment.BottomStart).padding(bottom = 26.dp),
        style = text(20, FontWeight.Medium, Color(0xFFA9B0C2), align = TextAlign.Center))
    Column(
        Modifier.align(Alignment.CenterEnd).width(180.dp).padding(end = 48.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
          RailButton("Style", enabled = ui.face.styles > 1, onTap = { onEvent(GlassEvent.CycleStyle) }) { StyleGlyph(it) }
          RailButton("Color", enabled = ui.choices() > 1, onTap = { onEvent(GlassEvent.ToggleColors) }) { PaletteGlyph(it) }
          RailButton("Done", on = true, onTap = { onEvent(GlassEvent.Done) }) { CheckGlyph(it) }
        }
    if (ui.colorsOpen) {
      // Colors first, then wallpapers; two columns once there are too many for one.
      val count = ui.choices()
      val twoColumns = count > 6
      Row(
          Modifier.offset(x = if (twoColumns) 810.dp else 880.dp, y = if (twoColumns) 130.dp else 210.dp)
              .frosted(radius = 40f)
              .padding(18.dp),
          horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            (0 until count).chunked(if (twoColumns) (count + 1) / 2 else count).forEach { column ->
              Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                column.forEach { i ->
                  val picked = ui.look().color == i
                  val swatch =
                      Modifier.size(56.dp)
                          .clip(CircleShape)
                          .background(if (picked) Color.White else Color.White.copy(alpha = 0.35f))
                          .padding(if (picked) 4.dp else 2.dp)
                          .clip(CircleShape)
                          .clickable(remember { MutableInteractionSource() }, null) { onEvent(GlassEvent.PickColor(i)) }
                  val palette = ui.face.palettes.getOrNull(i)
                  if (palette != null) Box(swatch.background(Color(palette.background)))
                  else wallpapers.getOrNull(i - ui.face.palettes.size)?.let { Image(it.thumb, null, swatch, contentScale = ContentScale.Crop) }
                }
              }
            }
          }
    }
  }
}

@Composable
private fun RailButton(label: String, on: Boolean = false, enabled: Boolean = true, onTap: () -> Unit, glyph: @Composable (Color) -> Unit) {
  val press = rememberPress()
  val ink = if (on) Color(0xFF111522) else Color.White
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
        Modifier.size(96.dp)
            .pressable(press, enabled = enabled, onTap = onTap)
            .frosted(press, on = on, radius = 48f)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        contentAlignment = Alignment.Center) {
          glyph(ink)
        }
    Spacer(Modifier.height(8.dp))
    BasicText(label, style = text(18, color = Color(0xFFC9CFDD).copy(alpha = if (enabled) 1f else 0.5f)))
    Spacer(Modifier.height(16.dp))
  }
}

@Composable
private fun StyleGlyph(ink: Color) =
    Canvas(Modifier.size(40.dp)) {
      val s = size.width / 24f
      val st = Stroke(1.9f * s, cap = StrokeCap.Round)
      fun l(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(ink, Offset(x0 * s, y0 * s), Offset(x1 * s, y1 * s), st.width, StrokeCap.Round)
      l(4f, 7f, 14f, 7f)
      l(18f, 7f, 20f, 7f)
      l(4f, 17f, 10f, 17f)
      l(14f, 17f, 20f, 17f)
      l(14f, 4f, 14f, 10f)
      l(10f, 14f, 10f, 20f)
    }

@Composable
private fun PaletteGlyph(ink: Color) =
    Canvas(Modifier.size(40.dp)) {
      val s = size.width / 24f
      drawCircle(ink, 9f * s, Offset(12 * s, 12 * s), style = Stroke(1.9f * s))
      drawCircle(ink, 1.4f * s, Offset(7.5f * s, 11 * s))
      drawCircle(ink, 1.4f * s, Offset(10.5f * s, 7.2f * s))
      drawCircle(ink, 1.4f * s, Offset(15.2f * s, 7.6f * s))
      drawCircle(ink, 2.2f * s, Offset(15.5f * s, 15f * s))
    }

@Composable
private fun CheckGlyph(ink: Color) =
    Canvas(Modifier.size(40.dp)) {
      val s = size.width / 24f
      val path =
          androidx.compose.ui.graphics.Path().apply {
            moveTo(5 * s, 12.5f * s)
            lineTo(9.5f * s, 17 * s)
            lineTo(19 * s, 7.5f * s)
          }
      drawPath(path, ink, style = Stroke(2.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

// --- the Home Screen ----------------------------------------------------------------------------

private sealed interface HomeItem {
  val label: String

  data class App(val entry: AppEntry) : HomeItem {
    override val label
      get() = entry.label
  }

  data class BuiltIn(override val label: String, val glyph: String, val fill: Color, val badge: Boolean = false, val onTap: () -> Unit) :
      HomeItem
}

private const val SETTINGS_FOLDER = "Settings"
private const val PAGE_SIZE = 15

@Composable
private fun GlassHomeScreen(
    frost: ImageBitmap?,
    /** Read while drawing, so a face change doesn't recompose the Home Screen. */
    fallback: () -> Color,
    weather: GlassWeather?,
    use24Hour: Boolean,
    heyPkg: String?,
    selfUpdate: SelfUpdateState,
    actions: GlassActions,
    onClose: () -> Unit,
) {
  val context = LocalContext.current
  var reload by remember { mutableStateOf(0) }
  DisposableEffect(Unit) {
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, i: Intent) {
            reload++
          }
        }
    val filter =
        IntentFilter().apply {
          addAction(Intent.ACTION_PACKAGE_ADDED)
          addAction(Intent.ACTION_PACKAGE_REMOVED)
          addAction(Intent.ACTION_PACKAGE_REPLACED)
          addDataScheme("package")
        }
    if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }
  val apps by produceState(emptyList<AppEntry>(), reload) { value = withContext(Dispatchers.IO) { loadApps(context) } }
  var settingsOpen by remember { mutableStateOf(false) }
  val settingsApps = apps.filter { it.folder == SETTINGS_FOLDER }
  val updateReady = selfUpdate.update != null
  val items =
      listOf(
          HomeItem.BuiltIn("Calls", GlassGlyphs.CALL, Color(0xFF1FA463), onTap = actions.calls),
          HomeItem.BuiltIn("App Store", GlassGlyphs.DOWNLOAD, Color(0xFF2D6CDF), onTap = actions.openStore),
          HomeItem.BuiltIn("Photo Frame", GlassGlyphs.IMAGE, Color(0xFFE0703A), onTap = actions.photoFrame),
          HomeItem.BuiltIn("Tools", GlassGlyphs.WIDGETS, Color(0xFF5A5F6A)) { context.startActivity(Intent(context, ToolsActivity::class.java)) },
          HomeItem.BuiltIn("Settings", GlassGlyphs.GEAR, Color(0xFF6B7185)) { settingsOpen = true },
          HomeItem.BuiltIn(
              selfUpdate.status ?: if (updateReady) "Update ready" else "Updates",
              if (updateReady) GlassGlyphs.DOWNLOAD else GlassGlyphs.REFRESH,
              if (updateReady) Color(0xFF2D6CDF) else Color(0xFF3A3F4B),
              badge = updateReady) { selfUpdate.tap(context) },
      ) + apps.filter { it.folder != SETTINGS_FOLDER }.map { HomeItem.App(it) }

  Box(
      Modifier.fillMaxSize()
          .clickable(remember { MutableInteractionSource() }, null) {}
          .swipeDownToClose(onClose)) {
        // In its own layer: [fallback] follows the face, and redrawing it here alone keeps a swipe
        // from re-recording the whole Home Screen behind it.
        Spacer(
            Modifier.fillMaxSize().graphicsLayer().drawBehind {
              if (frost != null) drawImage(frost, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
              else drawRect(fallback())
              drawRect(Brush.verticalGradient(listOf(Color(0x8A0C143C), Color(0xB3060A1E))))
            })
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 36.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            HomeClock(use24Hour, Modifier.weight(1f))
            if (heyPkg != null) {
              RoundGlassButton(onTap = { fireHey(context, heyPkg) }) { MicGlyphSmall() }
              Spacer(Modifier.width(14.dp))
            }
            RoundGlassButton(onTap = onClose) { Chevron(up = false, size = 30) }
          }
          Spacer(Modifier.height(24.dp))
          Row(Modifier.fillMaxWidth().height(560.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
              WeatherWidget(weather, Modifier.weight(1f).fillMaxWidth())
              NowPlayingWidget(Modifier.height(170.dp).fillMaxWidth())
            }
            AppPages(items, actions.launch, Modifier.weight(1f).fillMaxHeight())
          }
        }
        val press = rememberPress()
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).height(40.dp).pressable(press, onTap = onClose).frosted(press, radius = 20f).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Chevron(up = false, size = 18)
              BasicText("Faces", style = text(18))
            }
        if (settingsOpen) {
          SettingsSheet(
              apps = settingsApps,
              onLaunch = {
                settingsOpen = false
                actions.launch(it)
              },
              onOpen = { target ->
                settingsOpen = false
                when (target) {
                  SettingsTarget.Immortal -> context.startActivity(Intent(context, ImmortalSettingsActivity::class.java))
                  SettingsTarget.Screensaver -> context.startActivity(Intent(context, ScreensaverSettingsActivity::class.java))
                  SettingsTarget.Help -> actions.openHelp()
                }
              },
              onDismiss = { settingsOpen = false })
        }
      }
}

/**
 * The date and time at the top of the Home Screen. Its own scope, ticking each minute: it shows
 * no seconds, and a tick read by the whole Home Screen recomposed fifteen app tiles every second.
 */
@Composable
private fun HomeClock(use24Hour: Boolean, modifier: Modifier) {
  val now = rememberNow(60_000)
  Column(modifier) {
    BasicText(DateFormatter.format(Date(now), "EEEEMMMMd").uppercase(Locale.getDefault()), style = text(22, color = Color.White.copy(alpha = 0.85f)))
    BasicText(
        SimpleDateFormat(if (use24Hour) "H:mm" else "h:mm a", Locale.getDefault()).format(Date(now)),
        style = text(22, FontWeight.Medium, Color.White.copy(alpha = 0.75f)))
  }
}

/** A downward swipe anywhere closes the Home Screen; sideways drags are left to the app pager. */
private fun Modifier.swipeDownToClose(onClose: () -> Unit): Modifier =
    pointerInput(Unit) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        while (true) {
          val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
          if (!c.pressed) return@awaitEachGesture
          val d = c.position - down.position
          if (d.getDistance() > slop) {
            if (d.y <= 0 || abs(d.y) < abs(d.x)) return@awaitEachGesture
            val end = consumeUntilUp(down.id) ?: return@awaitEachGesture
            if (end.y - down.position.y > 90) onClose()
            return@awaitEachGesture
          }
        }
      }
    }

@Composable
private fun RoundGlassButton(onTap: () -> Unit, content: @Composable () -> Unit) {
  val press = rememberPress()
  Box(Modifier.size(64.dp).pressable(press, onTap = onTap).frosted(press, radius = 32f), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun MicGlyphSmall() =
    Canvas(Modifier.size(28.dp)) {
      val w = size.minDimension
      val stroke = Stroke(w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
      drawRoundRect(Color.White, Offset(w * 0.38f, w * 0.16f), Size(w * 0.24f, w * 0.40f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = stroke)
      drawArc(Color.White, 20f, 140f, false, Offset(w * 0.26f, w * 0.24f), Size(w * 0.48f, w * 0.48f), style = stroke)
      drawLine(Color.White, Offset(w * 0.5f, w * 0.72f), Offset(w * 0.5f, w * 0.84f), w * 0.08f)
    }

@Composable
private fun WeatherWidget(weather: GlassWeather?, modifier: Modifier) {
  Column(
      modifier.frosted(radius = 40f, fill = Color(0xA65A93FF)).padding(28.dp),
      verticalArrangement = Arrangement.SpaceBetween) {
        if (weather == null) {
          BasicText("Weather", style = text(26))
          BasicText("--", style = text(110, FontWeight.Light))
          BasicText("Waiting for a forecast", style = text(22, FontWeight.Medium, Color.White.copy(alpha = 0.85f)))
        } else {
          BasicText(weather.city.ifBlank { "Home" }, style = text(26))
          BasicText("${weather.temp}°", style = text(110, FontWeight.Light))
          Column {
            BasicText(weatherLabel(weather.code), style = text(24))
            if (weather.hi != null && weather.lo != null) BasicText("H:${weather.hi}° L:${weather.lo}°", style = text(22, FontWeight.Medium, Color.White.copy(alpha = 0.85f)))
          }
        }
      }
}

@Composable
private fun NowPlayingWidget(modifier: Modifier) {
  val context = LocalContext.current
  var playing by remember { mutableStateOf(NowPlayingHub.current) }
  val main = remember { Handler(Looper.getMainLooper()) }
  DisposableEffect(Unit) {
    val l = NowPlayingHub.Listener { s -> main.post { playing = s } }
    NowPlayingHub.addListener(l)
    onDispose { NowPlayingHub.removeListener(l) }
  }
  val np = playing
  val art by
      produceState<android.graphics.Bitmap?>(null, np?.artBitmap, np?.artUrl) {
        value = np?.artBitmap ?: np?.artUrl?.takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.IO) { MediaArt.resolveUri(context, it) } }
      }
  val press = rememberPress()
  Row(
      modifier.pressable(press, enabled = np != null) { NowPlayingHub.playPause() }.frosted(press, radius = 40f, fill = Color(0xB3F0784A)).padding(22.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(
            Modifier.size(120.dp).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFFFFD27A), Color(0xFFFF6F91), Color(0xFF7A5CFF)))),
            contentAlignment = Alignment.Center) {
              art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        Column(Modifier.weight(1f)) {
          BasicText(
              when (np?.state) {
                PlaybackState.PLAYING -> "NOW PLAYING"
                PlaybackState.PAUSED -> "PAUSED"
                else -> "MUSIC"
              },
              style = text(16, color = Color.White.copy(alpha = 0.85f)))
          BasicText(np?.title ?: "Nothing playing", style = text(24), maxLines = 1, overflow = TextOverflow.Ellipsis)
          BasicText(
              np?.artist?.takeIf { it.isNotBlank() } ?: if (np == null) "Play music to see it here" else "",
              style = text(20, FontWeight.Medium, Color.White.copy(alpha = 0.85f)),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis)
        }
      }
}

@Composable
private fun AppPages(items: List<HomeItem>, launch: (ComponentName) -> Unit, modifier: Modifier) {
  val pages = items.chunked(PAGE_SIZE)
  val pager = rememberPagerState { pages.size.coerceAtLeast(1) }
  Column(modifier) {
    HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 0) { page ->
      val pageItems = pages.getOrElse(page) { emptyList() }
      Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        pageItems.chunked(5).forEach { row ->
          Row(Modifier.fillMaxWidth()) {
            row.forEach { item -> AppCell(item, launch, Modifier.weight(1f)) }
            repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
          }
        }
      }
    }
    if (pages.size > 1) {
      Row(Modifier.fillMaxWidth().padding(bottom = 64.dp), horizontalArrangement = Arrangement.Center) {
        repeat(pages.size) { i ->
          Box(
              Modifier.padding(horizontal = 5.dp)
                  .size(9.dp)
                  .clip(CircleShape)
                  .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.4f)))
        }
      }
    }
  }
}

@Composable
private fun AppCell(item: HomeItem, launch: (ComponentName) -> Unit, modifier: Modifier) {
  val press = rememberPress()
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
        Modifier.size(112.dp)
            .pressable(press) {
              when (item) {
                is HomeItem.App -> launch(item.entry.component)
                is HomeItem.BuiltIn -> item.onTap()
              }
            }
            .frosted(press, radius = 32f, fill = (item as? HomeItem.BuiltIn)?.fill?.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center) {
          when (item) {
            is HomeItem.App -> Image(item.entry.icon, item.label, Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)))
            is HomeItem.BuiltIn -> {
              VectorGlyph(item.glyph, 50)
              if (item.badge) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp).clip(CircleShape).background(Color(0xFFE53935)))
            }
          }
        }
    Spacer(Modifier.height(8.dp))
    BasicText(item.label, style = text(17, FontWeight.Medium, align = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun VectorGlyph(pathData: String, size: Int) {
  val path = remember(pathData) { PathParser().parsePathString(pathData).toPath() }
  Canvas(Modifier.size(size.dp)) {
    val s = this.size.minDimension / 24f
    scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
  }
}

private enum class SettingsTarget { Immortal, Screensaver, Help }

@Composable
private fun SettingsSheet(apps: List<AppEntry>, onLaunch: (ComponentName) -> Unit, onOpen: (SettingsTarget) -> Unit, onDismiss: () -> Unit) {
  Box(
      Modifier.fillMaxSize().background(Color(0x8C000000)).clickable(remember { MutableInteractionSource() }, null) { onDismiss() },
      contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .background(Color(0xFA1C2030), RoundedCornerShape(44.dp))
                .frosted(radius = 44f, fill = Color.Transparent)
                .padding(vertical = 18.dp)) {
              BasicText("Settings", Modifier.padding(start = 28.dp, bottom = 8.dp), style = text(28))
              SheetRow("Immortal", GlassGlyphs.GEAR) { onOpen(SettingsTarget.Immortal) }
              SheetRow("Screensaver and clock faces", GlassGlyphs.IMAGE) { onOpen(SettingsTarget.Screensaver) }
              SheetRow("Help", GlassGlyphs.HELP) { onOpen(SettingsTarget.Help) }
              apps.forEach { app -> SheetRow(app.label, icon = app.icon) { onLaunch(app.component) } }
            }
      }
}

@Composable
private fun SheetRow(label: String, glyph: String? = null, icon: ImageBitmap? = null, onTap: () -> Unit) {
  Row(
      Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 28.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        if (icon != null) Image(icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
        else if (glyph != null) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { VectorGlyph(glyph, 30) }
        BasicText(label, style = text(22, FontWeight.Medium))
      }
}

/** Material glyph paths (24x24) for the Home Screen's built-in tiles. */
private object GlassGlyphs {
  const val CALL =
      "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"
  const val DOWNLOAD =
      "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
  const val REFRESH =
      "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
  const val IMAGE =
      "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"
  const val WIDGETS =
      "M3 3h8v8H3V3zm10 0h8v5h-8V3zM3 13h5v8H3v-8zm7 0h11v8H10v-8z"
  const val HELP =
      "M11 18h2v-2h-2v2zm1-16C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm0-14c-2.21 0-4 1.79-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 2-3 1.75-3 5h2c0-2.25 3-2.5 3-5 0-2.21-1.79-4-4-4z"
  const val GEAR =
      "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"
}

/** A wallpaper file and the small copy its swatch shows. */
private class Wallpaper(val file: java.io.File, val thumb: ImageBitmap)

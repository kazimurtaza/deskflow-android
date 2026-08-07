/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.tfv.deskflow.services

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.media.AudioManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import arrow.core.raise.catch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.tfv.deskflow.R
import org.tfv.deskflow.client.events.KeyboardEvent
import org.tfv.deskflow.client.events.MouseEvent
import org.tfv.deskflow.client.events.MouseButton
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.input.wheelScrollEndpoints
import org.tfv.deskflow.client.models.ClipboardData
import org.tfv.deskflow.client.util.Keyboard
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.data.appPrefsStore
import org.tfv.deskflow.components.GlobalKeyboardManager
import org.tfv.deskflow.ext.ScreenSize
import org.tfv.deskflow.ext.getScreenSize
import org.tfv.deskflow.ext.sendServiceConnectionEvent

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
@SuppressLint("ServiceCast", "NewApi")
class GlobalInputService : AccessibilityService() {

  private var pickerShownForPackage: String? = null

  /** Client responsible for communicating with the connection service. */
  private lateinit var serviceClient: ConnectionServiceClient

  /**
   * Keyboard manager for handling keyboard events, calculating state and
   * triggering actions.
   */
  private lateinit var keyboardManager: GlobalKeyboardManager

  /** Bridges Space→play/pause to the active media session (YouTube, Spotify, …). */
  private val mediaSessionController by lazy { MediaSessionController(this) }

  /** Guards the one-time "enable notification access" nudge for media control. */
  @Volatile private var mediaAccessNudgeShown = false

  private val clipboard by lazy {
    getSystemService(ClipboardManager::class.java)
  }

  private val imeManager by lazy {
    getSystemService(InputMethodManager::class.java)
  }

  private val notificationManager by lazy {
    getSystemService(NotificationManager::class.java)
  }

  private val audio by lazy {
    getSystemService(AudioManager::class.java)
  }

  private val powerManager by lazy {
    getSystemService(PowerManager::class.java)
  }

  private val displayManager by lazy {
    getSystemService(DisplayManager::class.java)
  }

  /** Invalidates [cachedScreenSize] on display changes (rotation/fold/refresh). */
  private var displayListener: DisplayManager.DisplayListener? = null

  /**
   * Flow to observe if the home screen is currently active. This is used to
   * determine if the current screen is the home screen.
   * > Example: Used for checking if the user is on the home screen in the
   * > global input service.
   */
  private val isHomeScreenActiveFlow = MutableStateFlow(false)

  /** Check if the home screen is currently active. */
  private val isHomeScreenActive: Boolean
    get() = isHomeScreenActiveFlow.value

  /**
   * Set of known home packages. Populated when the service is connected via
   * `fetchHomePackages`.
   */
  private val knownHomePackages = mutableSetOf<String>()

  private val activePackageName: String?
    get() = rootInActiveWindow?.packageName?.toString()

  private val keyboardWindowInfo: AccessibilityWindowInfo?
    get() =
      windows.find { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

  private val isKeyboardOpened: Boolean
    get() = keyboardWindowInfo != null

  /**
   * Flag to indicate if a global input action is currently pending. This is
   * used to prevent multiple gestures from being dispatched at the same time.
   */
  @Volatile private var globalInputPending = false

  /**
   * Handler for posting global input actions to the main thread. This is used
   * to ensure that gestures are dispatched on the main thread.
   */
  private val globalInputHandler = Handler(Looper.getMainLooper())

  /**
   * Callback for gesture results. This is used to handle the completion or
   * cancellation of gestures.
   */
  private val gestureResultCallback =
    object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        super.onCompleted(gestureDescription)
        globalInputPending = false
        // A wheel event may have arrived while this gesture was in flight
        // (flushWheelScroll bails while globalInputPending is true); flush any
        // accumulated delta now that the dispatch slot is free again.
        flushWheelScroll()
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        super.onCancelled(gestureDescription)
        globalInputPending = false
        flushWheelScroll()
      }
    }

  /**
   * Layout parameters for the mouse pointer view. This is used to position the
   * mouse pointer on the screen.
   */
  private lateinit var mousePointerLayout: WindowManager.LayoutParams

  /**
   * View for the mouse pointer. This is the view that will be displayed on the
   * screen to represent the mouse pointer.
   */
  private lateinit var mousePointerView: View

  /**
   * Flag to indicate if the mouse pointer is currently visible. This is used to
   * control the visibility of the mouse pointer view.
   */
  @Volatile private var mousePointerVisible = false

  /**
   * Mouse button currently held down (null if none). Used for click-vs-drag
   * detection: a press followed by movement beyond [dragThreshold] becomes a
   * drag (a continued accessibility gesture); otherwise it is a tap on release.
   */
  private data class MouseButtonState(
    val buttonId: UInt,
    val downX: Int,
    val downY: Int,
    val downTime: Long = System.currentTimeMillis(),
  )

  @Volatile private var mouseButtonDown: MouseButtonState? = null

  /**
   * Active drag state, non-null while a touch hold / drag is in progress.
   * [lastStrokes] holds one stroke per simulated finger for multi-touch
   * (right/middle clicks): index 0 = primary, 1 = +100px right, 2 = +200px.
   */
  private data class DragState(
    var lastDispatchedX: Float,
    var lastDispatchedY: Float,
    var targetX: Float,
    var targetY: Float,
    var lastStrokes: List<StrokeDescription> = emptyList(),
    var isEnding: Boolean = false,
    var initialHoldDuration: Long = 0,
    val fingerCount: Int = 1,
  )

  @Volatile private var activeDragState: DragState? = null

  /** True while a drag gesture segment is mid-flight (prevents overlapping dispatch). */
  @Volatile private var dragGestureInProgress = false

  /** Movement (px) beyond which a held button-down becomes a drag. */
  private val dragThreshold = 10

  /** Per-finger X/Y offsets for simulated multi-touch drags. */
  private val multiTouchFingerOffsets =
    listOf(Pair(0, 0), Pair(100, 0), Pair(200, 0))

  /** Pending delayed IME-picker show on disconnect (cancelled on reconnect). */
  private var imePickerDelayJob: Job? = null

  private val keyboardWasOpen = AtomicBoolean(false)

  /** WindowManager instance for managing the mouse pointer view. */
  private lateinit var windowManager: WindowManager

  private val serviceScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private fun createNotificationChannel() {

    val name =
      resources.getText(R.string.global_input_service_notification_channel_name)
    val desc =
      resources.getText(
        R.string.global_input_service_notification_channel_description
      )
    val importance = NotificationManager.IMPORTANCE_DEFAULT
    val chan =
      NotificationChannel(CHANNEL_ID, name, importance).apply {
        description = desc.toString()
      }
    notificationManager.createNotificationChannel(chan)
  }

  private fun sendStatusNotification(
    text: String,
    notificationId: Int = NOTIF_IME_NOT_SETUP_ID,
    customizer: (NotificationCompat.Builder.() -> Unit)? = null,
  ): Boolean {
    if (
      ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.POST_NOTIFICATIONS,
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      log.warn {
        "POST_NOTIFICATIONS permission not granted, cannot send notification."
      }
      return false
    }
    val notificationBuilder =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.deskflow_icon_fit) // your icon
        .setContentTitle(
          resources.getText(
            R.string.global_input_service_notification_channel_name
          )
        )
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
    if (customizer != null) {
      customizer(notificationBuilder)
    }

    val notification = notificationBuilder.build()
    // TODO: Add action to open keyboard settings
    NotificationManagerCompat.from(this)
      .notify(notificationId, notification)
    return true
  }

  private fun isDeskflowKeyboardActive(
    imeInfo: InputMethodInfo? = imeManager.currentInputMethodInfo
  ): Boolean {
    return imeInfo?.let { current -> deskflowImeInfo?.id == current.id }
      ?: false
  }

  @SuppressLint("NewApi")
  private fun checkIMESetup() {
    val kbOpen = isKeyboardOpened
    val kbWasOpen = keyboardWasOpen.load()
    if (!kbOpen) {
      keyboardWasOpen.store(false)
      return
    }

    if (kbWasOpen) return

    if (!serviceClient.state.isConnected) return

    keyboardWasOpen.store(true)

    val imeInfo = imeManager.currentInputMethodInfo
    if (imeInfo != null) {
      log.debug { "Current IME: ${imeInfo.packageName}" }
      when {
        isDeskflowKeyboardActive(imeInfo) -> {
          log.debug { "Current IME is deskflow, no action needed." }
        }
        !isDeskflowImeEnabled -> {
          log.warn { "IME is not enabled, showing notification." }
          nudgeToSettings(
            NOTIF_IME_NOT_SETUP_ID,
            resources.getString(
              R.string.global_input_service_notification_ime_not_setup
            ),
            android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS,
          )
        }
        with(activePackageName) {
          this == null || pickerShownForPackage == this
        } -> {
          log.debug {
            "IME picker was already shown for package $pickerShownForPackage"
          }
        }
        else -> {
          log.debug {
            "IME is enabled, but not active. Previous picker was shown for package $pickerShownForPackage"
          }

          pickerShownForPackage = activePackageName
          val imeId = deskflowImeInfo?.id
          if (
            imeId != null && softKeyboardController.switchToInputMethod(imeId)
          ) {
            log.debug { "softKeyboardController set IME to $imeId" }
            return
          }

          imeManager.showInputMethodPicker()
        }
      }
    } else {
      log.warn { "No current IME found." }
    }
  }

  @SuppressLint("RtlHardcoded")
  override fun onCreate() {
    super.onCreate()
    log.debug { "onCreate:${GlobalInputService::class.simpleName}" }

    createNotificationChannel()

    keyboardManager = GlobalKeyboardManager(this)
    serviceScope.launch {
      keyboardManager.actionFlow.collect { action ->
        log.debug { "Triggered Action: ${action.label}(${action.actionId})" }
        when (action.actionId) {
          GLOBAL_ACTION_DPAD_CENTER -> {
            clickFocused()
          }

          else -> {
            performGlobalAction(action.actionId)
          }
        }
      }
    }

    // Keep the pointer-sensitivity multiplier in sync with user preferences.
    serviceScope.launch {
      appPrefsStore.data.collect { prefs ->
        mouseSensitivity = if (prefs.mouseSensitivity == 0.0f) 1.0f else prefs.mouseSensitivity
      }
    }

    serviceClient =
      ConnectionServiceClient(this) { event ->
        globalInputHandler.post {
          try {
            when (event) {
              is MouseEvent -> {
                onMouseEvent(event)
              }

              is KeyboardEvent -> {
                onKeyboardEvent(event)
              }

              is ScreenEvent.SetClipboard -> {
                val data = event.data
                log.debug { "SetClipboard(variantCount=${data.variants.size})" }
                val knownFormats =
                  arrayOf(
                    ClipboardData.Format.Text // ClipboardData.Format.Bitmap
                  )

                for (format in knownFormats) {
                  val variant = data.variants[format] ?: continue

                  // TODO: Implement `Converter` concept
                  val clipData =
                    when (format) {
                      ClipboardData.Format.Text -> {
                        log.debug { "SetClipboard: Text(${variant.data.size})" }
                        val text = String(variant.data, Charsets.UTF_8)
                        ClipData.newPlainText("deskflow_clipboard", text)
                      }

                      else -> {
                        log.warn {
                          "SetClipboard: No converter for format $format"
                        }
                        continue
                      }
                    }

                  log.debug { "Clipboard variant ready: $variant" }
                  // Mark remote-injected clipboard as sensitive so Android 13+
                  // masks the paste preview (content arriving from the server is
                  // unknown and may include passwords). [ClipDescription.setExtras]
                  // is a public API since API 33.
                  clipData.description.extras =
                    PersistableBundle().apply {
                      putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                  clipboard.setPrimaryClip(clipData)
                  break
                }
              }

              is ScreenEvent.Enter -> {
                // Re-sync the pointer delta baseline on screen transitions so the
                // first move after (re-)entry is an absolute jump, not a scaled
                // delta from a stale server position.
                lastServerX = Int.MIN_VALUE
                lastServerY = Int.MIN_VALUE
                cachedScreenSize = null // recompute on (re-)entry (rotation may have occurred)
                // Pointer is back on this screen: re-show the cursor overlay
                // (hidden on Leave). Idempotent + overlay-permission-guarded.
                showMousePointer()
              }

              is ScreenEvent.Leave -> {
                lastServerX = Int.MIN_VALUE
                lastServerY = Int.MIN_VALUE
                cachedScreenSize = null
                // Pointer left this screen: hide the local cursor so it isn't
                // left stranded on a panel the user can't see/interact with.
                hideMousePointer()
              }

              else -> {}
            }
          } catch (err: Exception) {
            log.error(err) {
              "Error running on globalInputHandler: ${err.message}"
            }
          }
        }
      }

    mousePointerView = View.inflate(baseContext, R.layout.mouse_pointer, null)
    mousePointerLayout =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
      )

    mousePointerLayout.layoutInDisplayCutoutMode =
      LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    mousePointerLayout.gravity = Gravity.TOP or Gravity.LEFT
    mousePointerLayout.x = 200
    mousePointerLayout.y = 200

    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

    // ConnectionService is a foreground service; start it with
    // startForegroundService so it can promote itself within the window (a plain
    // startService from this background accessibility context can be rejected
    // with BackgroundServiceStartNotAllowedException on Android 12+).
    ContextCompat.startForegroundService(
      this,
      Intent(applicationContext, ConnectionService::class.java),
    )
    serviceClient.bind()

    // On disconnect, give the user a chance to switch back to their normal
    // keyboard: show the system IME picker after a short delay (cancelled if the
    // connection returns). Hosted here because the accessibility service stays
    // alive regardless of whether an input field is focused.
    serviceScope.launch {
      var previouslyConnected: Boolean? = null
      serviceClient.stateFlow.collect { state ->
        val connected = state.isConnected && state.ackReceived && state.isEnabled
        if (connected) {
          imePickerDelayJob?.cancel()
          imePickerDelayJob = null
        } else if (previouslyConnected == true) {
          imePickerDelayJob?.cancel()
          imePickerDelayJob = serviceScope.launch {
            delay(IME_PICKER_DELAY_MS)
            withContext(Dispatchers.Main) {
              runCatching { imeManager.showInputMethodPicker() }
                .onFailure { log.warn(it) { "Error showing IME picker" } }
            }
          }
        }
        previouslyConnected = connected
      }
    }
  }

  /**
   * Handle keyboard events from the client. This is where we process keyboard
   * events and pass them to the keyboard manager.
   * > Example: Used for handling key presses in the global input service.
   */
  private fun onKeyboardEvent(event: KeyboardEvent) {
    log.debug { "onKeyboardEvent: $event" }
    // Volume keys are handled directly via the AudioManager (they are not
    // GLOBAL_ACTIONs), and only on key-down: the Barrier protocol delivers both
    // Down and Up, so acting on both would step the volume twice per press.
    if (event.type == KeyboardEvent.Type.Down && handleVolumeKey(event)) return
    // Always process modifier keys on both Down and Up so keyboardManager tracks
    // modifier state (e.g. Control for Ctrl+wheel zoom).
    if (Keyboard.findModifierKey(event.id.toInt()) != null) {
      keyboardManager.process(event)
      return
    }
    // Space toggles play/pause of the active media session (YouTube, Spotify, …). There is
    // no system-wide key injection here, so we talk to the MediaSession directly via
    // MediaSessionController. Constraints, ordered cheap-first: Down only (a held Space
    // toggles once, not per key-repeat), no momentary modifiers held (so Ctrl/Alt/Shift/Meta
    // +Space still reaches the shortcut matcher below), no focused editable field and the
    // IME window closed (otherwise the IME types the space).
    // TODO: the editable/IME-open gate is a local heuristic that duplicates the per-action
    //   `ignoreIME` flag (parsed in GlobalKeyboardManager but never consulted in dispatch);
    //   reconcile by wiring ignoreIME into this path.
    if (event.type == KeyboardEvent.Type.Down &&
        event.id.toInt().toChar() == ' ' &&
        !hasMomentaryModifiers(event) &&
        !isKeyboardOpened &&
        findFocus(FOCUS_INPUT)?.isEditable != true &&
        toggleMediaOrNudge()
    ) return
    // Non-modifier keys: handle Down and Repeat (key-repeat); ignore Up, which
    // would otherwise duplicate the Down.
    if (event.type == KeyboardEvent.Type.Up) return
    keyboardManager.process(event)
  }

  /** True if any momentary (non-lock) modifier is held — i.e. this is not a "bare" key press. */
  private fun hasMomentaryModifiers(event: KeyboardEvent): Boolean {
    val m = event.getModifiers()
    return m.isControl || m.isAlt || m.isMeta || m.isSuper || m.isShift || m.isAltGr
  }

  /** @return true if [event] was a volume/media key that was handled here. */
  private fun handleVolumeKey(event: KeyboardEvent): Boolean {
    when (event.id.toInt()) {
      Keyboard.SpecialKey.VolumeUp.code ->
        audio.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
      Keyboard.SpecialKey.VolumeDown.code ->
        audio.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
      Keyboard.SpecialKey.VolumeMute.code ->
        audio.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
      else -> return false
    }
    return true
  }

  /**
   * Toggle the active media session's play/pause. If Deskflow lacks Notification access
   * (required to read media sessions), nudge the user once with a deep link instead.
   *
   * @return true if a media transport command was dispatched (caller should consume the key).
   */
  private fun toggleMediaOrNudge(): Boolean {
    if (!mediaSessionController.isNotificationAccessEnabled()) {
      nudgeNotificationAccessOnce()
      return false
    }
    return mediaSessionController.togglePlayPause()
  }

  /**
   * Post a status notification whose content intent opens [settingsAction]. Returns whether
   * the notification was actually posted (false if POST_NOTIFICATIONS is denied).
   */
  private fun nudgeToSettings(
    notificationId: Int,
    message: String,
    settingsAction: String,
  ): Boolean =
    sendStatusNotification(message, notificationId) {
      setContentIntent(
        PendingIntent.getActivity(
          this@GlobalInputService,
          0,
          Intent(settingsAction),
          PendingIntent.FLAG_IMMUTABLE,
        )
      )
    }

  /** Show a single notification pointing at Notification access settings (for media control). */
  private fun nudgeNotificationAccessOnce() {
    if (mediaAccessNudgeShown) return
    // Latch only on a successful post: if POST_NOTIFICATIONS is denied the nudge can't show,
    // so we must not latch (else it stays suppressed forever, even after the user grants it).
    if (nudgeToSettings(
        NOTIF_MEDIA_ACCESS_ID,
        resources.getString(R.string.global_input_service_notification_media_access),
        android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
      )
    ) mediaAccessNudgeShown = true
  }

  /**
   * Called when the service is destroyed. This is where we clean up resources
   * and remove the mouse pointer view.
   */
  override fun onDestroy() {
    serviceScope.cancel()
    serviceClient.unbind()
    displayListener?.let { displayManager.unregisterDisplayListener(it) }
    displayListener = null
    resetDragState()
    // Mirror the onServiceConnected registrations so a recreate inside a surviving
    // process doesn't accumulate listeners / leak the keyboard manager's worker thread.
    clipboard.removePrimaryClipChangedListener(onClipboardChanged)
    catch({ keyboardManager.dispose() }) { err: Throwable ->
      log.error(err) { "Error disposing keyboard manager" }
    }
    // Only setupMousePointer()/showMousePointer() ever addView()s the pointer;
    // removing it unconditionally throws IllegalArgumentException (and crashes
    // the service) when never added.
    hideMousePointer()
    super.onDestroy()
  }

  /**
   * If a drag is in progress, feed the new (post-sensitivity) pointer position
   * into the drag state machine. MUST run after [moveMousePointer] so the
   * position reflects pointer-speed scaling.
   */
  private fun advanceDragIfActive(currentX: Int, currentY: Int) {
    val buttonState = mouseButtonDown ?: return
    val dragState = activeDragState ?: return
    if (dragState.initialHoldDuration == 0L) {
      // Speculative hold: convert to a drag once the cursor exceeds the slop.
      if (isDragMovement(buttonState.downX, buttonState.downY, currentX, currentY)) {
        val held = System.currentTimeMillis() - buttonState.downTime
        log.debug { "Converting hold to drag at [$currentX,$currentY] held ${held}ms" }
        dragState.initialHoldDuration = held
        dragState.targetX = currentX.toFloat()
        dragState.targetY = currentY.toFloat()
        if (!dragGestureInProgress) dispatchDragContinuation()
      } else {
        // Within tap slop: keep the target synced for a later conversion.
        dragState.targetX = currentX.toFloat()
        dragState.targetY = currentY.toFloat()
      }
    } else {
      // Already dragging: extend the path to the new position.
      updateDragGesture(currentX.toFloat(), currentY.toFloat())
    }
  }

  /**
   * Start a speculative touch hold (willContinue) that can convert to a drag on
   * movement, or release cleanly on button-up. [fingerCount] simulates
   * multi-touch for right (2) / middle (3) clicks.
   */
  private fun startSpeculativeHold(x: Float, y: Float, fingerCount: Int = 1) {
    if (activeDragState != null) {
      log.warn { "Speculative hold ignored - drag already active" }
      return
    }
    val fingers = fingerCount.coerceIn(1, 3)
    log.debug { "Starting speculative hold at [$x,$y] fingers=$fingers" }
    dragGestureInProgress = true

    val strokes = mutableListOf<StrokeDescription>()
    val builder = GestureDescription.Builder()
    for (i in 0 until fingers) {
      val (offX, offY) = multiTouchFingerOffsets[i]
      val path = Path().apply { moveTo(x + offX, y + offY) }
      val stroke = StrokeDescription(path, (i * 5).toLong(), 100, true)
      strokes.add(stroke)
      builder.addStroke(stroke)
    }

    activeDragState =
      DragState(
        lastDispatchedX = x,
        lastDispatchedY = y,
        targetX = x,
        targetY = y,
        lastStrokes = strokes,
        initialHoldDuration = 0,
        fingerCount = fingers,
      )

    safeDispatch(builder.build(), object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        dragGestureInProgress = false
        // willContinue completes immediately; the drag state stays armed, waiting
        // for movement (-> drag) or button-up (-> release). Do NOT clear it here.
        // A very fast click may have queued an end while this was in flight:
        if (activeDragState?.isEnding == true) dispatchFinalStroke()
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        log.warn { "Speculative hold cancelled" }
        resetDragState()
      }
    }, globalInputHandler)
  }

  /** Update the drag target; dispatch a continuation now if no segment is in flight. */
  private fun updateDragGesture(toX: Float, toY: Float) {
    val dragState = activeDragState ?: return
    dragState.targetX = toX
    dragState.targetY = toY
    if (!dragGestureInProgress) dispatchDragContinuation()
  }

  /** Continue each finger's stroke from the last dispatched point to the target. */
  private fun dispatchDragContinuation() {
    val dragState = activeDragState ?: return
    if (dragState.isEnding) {
      dispatchFinalStroke()
      return
    }
    if (dragState.lastDispatchedX == dragState.targetX &&
      dragState.lastDispatchedY == dragState.targetY
    ) {
      return // already at target
    }
    val lastStrokes = dragState.lastStrokes
    if (lastStrokes.isEmpty()) {
      log.warn { "No last strokes to continue from" }
      return
    }
    val fromX = dragState.lastDispatchedX
    val fromY = dragState.lastDispatchedY
    val toX = dragState.targetX
    val toY = dragState.targetY
    log.debug { "Drag continuation [$fromX,$fromY]->[$toX,$toY] fingers=${lastStrokes.size}" }
    dragGestureInProgress = true

    val continued = mutableListOf<StrokeDescription>()
    val builder = GestureDescription.Builder()
    for ((i, last) in lastStrokes.withIndex()) {
      val (offX, offY) = multiTouchFingerOffsets[i]
      val path = Path().apply {
        moveTo(fromX + offX, fromY + offY)
        lineTo(toX + offX, toY + offY)
      }
      val stroke = last.continueStroke(path, 0, 50, true)
      continued.add(stroke)
      builder.addStroke(stroke)
    }
    dragState.lastStrokes = continued
    dragState.lastDispatchedX = toX
    dragState.lastDispatchedY = toY

    safeDispatch(builder.build(), object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        dragGestureInProgress = false
        // If the cursor moved while this segment was in flight, chain another.
        if (activeDragState != null) dispatchDragContinuation()
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        log.warn { "Drag continuation cancelled" }
        resetDragState()
      }
    }, globalInputHandler)
  }

  /** Mark the active drag for ending at [endX],[endY]; dispatch the final lift. */
  private fun endDragGesture(endX: Float, endY: Float) {
    val dragState = activeDragState ?: run {
      log.warn { "endDragGesture called but no active drag state" }
      return
    }
    dragState.targetX = endX
    dragState.targetY = endY
    dragState.isEnding = true
    if (dragGestureInProgress) return // the in-flight segment's onCompleted will finalize
    dispatchFinalStroke()
  }

  /** Final stroke that lifts every held finger (willContinue = false). */
  private fun dispatchFinalStroke() {
    val dragState = activeDragState ?: return
    val lastStrokes = dragState.lastStrokes
    if (lastStrokes.isEmpty()) {
      activeDragState = null
      return
    }
    val endX = dragState.targetX
    val endY = dragState.targetY
    log.debug {
      "Drag end at [$endX,$endY] fingers=${lastStrokes.size} (held ${dragState.initialHoldDuration}ms)"
    }
    dragGestureInProgress = true

    val builder = GestureDescription.Builder()
    for ((i, last) in lastStrokes.withIndex()) {
      val (offX, offY) = multiTouchFingerOffsets[i]
      val path = Path().apply {
        moveTo(dragState.lastDispatchedX + offX, dragState.lastDispatchedY + offY)
        lineTo(endX + offX, endY + offY)
      }
      builder.addStroke(last.continueStroke(path, 0, 10, false))
    }

    safeDispatch(builder.build(), object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        resetDragState()
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        log.warn { "Drag end cancelled" }
        resetDragState()
      }
    }, globalInputHandler)
  }

  /**
   * Dispatch an accessibility gesture, guaranteeing that any drag/global-input state
   * armed before the dispatch is released if [dispatchGesture] throws. Without this, a
   * throw leaves [dragGestureInProgress] / [globalInputPending] / [activeDragState]
   * armed with no callback ever firing to clear them, wedging all subsequent mouse
   * input. Runs on the main looper; safe to call from gesture callbacks (which are
   * NOT covered by the outer onReceive catch).
   */
  private fun safeDispatch(
    gesture: GestureDescription,
    callback: GestureResultCallback,
    handler: Handler = globalInputHandler,
  ) {
    try {
      dispatchGesture(gesture, callback, handler)
    } catch (err: Exception) {
      log.error(err) { "dispatchGesture threw; resetting input state" }
      globalInputPending = false
      resetDragState()
    }
  }

  /** Clear ALL drag state; called from every drag callback's onCancelled and teardown. */
  private fun resetDragState() {
    activeDragState = null
    dragGestureInProgress = false
    mouseButtonDown = null
  }

  /** True if movement from (start) to (current) exceeds [dragThreshold]. */
  private fun isDragMovement(startX: Int, startY: Int, currentX: Int, currentY: Int): Boolean {
    val dx = abs(currentX - startX)
    val dy = abs(currentY - startY)
    return sqrt((dx * dx + dy * dy).toDouble()) > dragThreshold
  }

  /** Two-finger spread gesture (zoom in) centered on the pointer. */
  private fun spreadGesture() {
    val px = mousePointerLayout.x.toFloat()
    val py = mousePointerLayout.y.toFloat()
    val path1 = Path().apply { moveTo(px + 25, py); lineTo(px + 75, py) }
    val path2 = Path().apply { moveTo(px - 25, py); lineTo(px - 75, py) }
    val stroke1 = StrokeDescription(path1, 0, 200, true)
    val stroke2 = StrokeDescription(path2, 5, 200, true)
    val gesture =
      GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
    log.debug { "Spread (zoom in) at [$px,$py]" }
    safeDispatch(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        // Release both fingers from their spread positions.
        val p1 = Path().apply { moveTo(px + 75, py) }
        val p2 = Path().apply { moveTo(px - 75, py) }
        val release =
          GestureDescription.Builder()
            .addStroke(stroke1.continueStroke(p1, 50, 50, false))
            .addStroke(stroke2.continueStroke(p2, 50, 50, false))
            .build()
        safeDispatch(release, gestureResultCallback, globalInputHandler)
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        log.warn { "Spread gesture cancelled" }
      }
    }, globalInputHandler)
  }

  /** Two-finger pinch gesture (zoom out) centered on the pointer. */
  private fun pinchGesture() {
    val px = mousePointerLayout.x.toFloat()
    val py = mousePointerLayout.y.toFloat()
    val path1 = Path().apply { moveTo(px + 75, py); lineTo(px + 25, py) }
    val path2 = Path().apply { moveTo(px - 75, py); lineTo(px - 25, py) }
    val stroke1 = StrokeDescription(path1, 0, 200, true)
    val stroke2 = StrokeDescription(path2, 5, 200, true)
    val gesture =
      GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
    log.debug { "Pinch (zoom out) at [$px,$py]" }
    safeDispatch(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        val p1 = Path().apply { moveTo(px + 25, py) }
        val p2 = Path().apply { moveTo(px - 25, py) }
        val release =
          GestureDescription.Builder()
            .addStroke(stroke1.continueStroke(p1, 50, 50, false))
            .addStroke(stroke2.continueStroke(p2, 50, 50, false))
            .build()
        safeDispatch(release, gestureResultCallback, globalInputHandler)
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        log.warn { "Pinch gesture cancelled" }
      }
    }, globalInputHandler)
  }

  /**
   * Cached full-screen size. WindowMetrics is non-trivial to compute and was
   * called per mouse-move; cached and invalidated on screen transitions and
   * window-state changes (rotation / fold) so clamp bounds stay correct.
   */
  @Volatile private var cachedScreenSize: ScreenSize? = null

  /** Screen size, cached; recomputed lazily after invalidation. */
  private fun screenPx(): ScreenSize =
    cachedScreenSize ?: getScreenSize().also { cachedScreenSize = it }

  /**
   * Move the mouse pointer to the specified coordinates. This is used to update
   * the position of the mouse pointer on the screen.
   * > Example: Used for mouse movement in the global input service.
   */
  private fun moveMousePointer(x: Int, y: Int) {
    // Keep the model position current even while the overlay is hidden (after a
    // Leave) so the first Move on re-Enter doesn't briefly flash at the old spot.
    mousePointerLayout.x = x
    mousePointerLayout.y = y
    if (!mousePointerVisible) return
    log.debug {
      val s = screenPx()
      "Cursor move to [${x}, ${y}] with size(${s.px.width},${s.px.height})"
    }
    windowManager.updateViewLayout(mousePointerView, mousePointerLayout)
  }

  /**
   * Move the cursor to the specified coordinates. This is used to move the
   * mouse pointer to a specific location on the screen.
   * > Example: Used for mouse movement in the global input service.
   */
  /** Configured pointer sensitivity (1.0 = track the server exactly). */
  @Volatile private var mouseSensitivity = 1.0f

  /**
   * Last absolute position reported by the server; `Int.MIN_VALUE` means "reset"
   * (next move jumps to the absolute position instead of scaling a delta). Reset
   * on screen enter/leave so the first move after a screen transition re-syncs.
   */
  @Volatile private var lastServerX = Int.MIN_VALUE
  @Volatile private var lastServerY = Int.MIN_VALUE

  /**
   * Scales the absolute server position's delta by [mouseSensitivity] and
   * clamps to the screen. First call after a baseline reset (or at sensitivity
   * 1.0) returns the raw position so the pointer re-syncs exactly.
   */
  private fun applyPointerSensitivity(serverX: Int, serverY: Int): Pair<Int, Int> {
    val s = mouseSensitivity
    if (s == 1.0f || lastServerX == Int.MIN_VALUE) {
      lastServerX = serverX
      lastServerY = serverY
      return serverX to serverY
    }
    val dx = (serverX - lastServerX) * s
    val dy = (serverY - lastServerY) * s
    lastServerX = serverX
    lastServerY = serverY
    val screen = screenPx().px
    val nx = (mousePointerLayout.x + dx).toInt().coerceIn(0, screen.width)
    val ny = (mousePointerLayout.y + dy).toInt().coerceIn(0, screen.height)
    return nx to ny
  }

  /**
   * Turn the screen on if it is currently off.
   *
   * SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP is the only route to wake
   * the display from a background context (the modern Activity flags need a
   * visible activity). Both flags are deprecated but remain the supported
   * mechanism for this case. Acquired with a short timeout + ON_AFTER_RELEASE so
   * it self-releases and hands control back to the normal screen-off timer.
   *
   * Guarded by [powerManager.isInteractive] so it is a cheap no-op while the
   * screen is already on. Caveat: once in deep Doze the TCP connection is
   * frozen, so no event arrives to trigger this -- reliable right after the
   * screen turns off or while charging, not after long idle on battery.
   */
  @Suppress("DEPRECATION")
  private fun wakeScreenIfAsleep() {
    if (powerManager.isInteractive) return
    try {
      powerManager
        .newWakeLock(
          PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
          "deskflow:mouse-wake",
        )
        .acquire(WAKE_ON_INPUT_MS)
    } catch (err: Exception) {
      log.warn(err) { "Failed to wake screen" }
    }
  }

  private fun onMouseEvent(event: MouseEvent) {
    // Any mouse activity from the server means the user is reaching for this
    // screen -- wake it if it's asleep so the pointer isn't moving on a dark
    // panel.
    wakeScreenIfAsleep()
    when (event.type) {
      MouseEvent.Type.Move -> {
        // The server streams ABSOLUTE positions (DMMV, since the server runs
        // with relativeMouseMoves=false), so a multiplier on the value itself
        // would be meaningless. Instead scale the per-update DELTA by the
        // configured sensitivity (clamped to the screen); the first move after
        // a baseline reset jumps to the absolute position. At sensitivity 1.0
        // this is a no-op (exact server tracking).
        val (nx, ny) = applyPointerSensitivity(event.x, event.y)
        moveMousePointer(nx, ny)
        advanceDragIfActive(nx, ny)
      }

      MouseEvent.Type.MoveRelative -> {
        val s = mouseSensitivity
        val nx = mousePointerLayout.x + (event.x * s).toInt()
        val ny = mousePointerLayout.y + (event.y * s).toInt()
        moveMousePointer(nx, ny)
        advanceDragIfActive(nx, ny)
      }

      MouseEvent.Type.Down -> {
        log.debug { "Down [$event]" }
        // Left/middle/right start a speculative touch hold that becomes a drag
        // if the cursor moves, or a tap on release. Back/forward (X1/X2) drive
        // system navigation, fired on press (no touch gesture).
        when (event.id) {
          MouseButton.LEFT, MouseButton.MIDDLE, MouseButton.RIGHT -> {
            // Ignore a second press while a drag is already in progress (mouse
            // chording / duplicate Down) so mouseButtonDown stays in sync with
            // the active drag.
            if (activeDragState != null) return
            mouseButtonDown =
              MouseButtonState(event.id, mousePointerLayout.x, mousePointerLayout.y)
            val fingers =
              when (event.id) {
                MouseButton.MIDDLE -> 3
                MouseButton.RIGHT -> 2
                else -> 1
              }
            startSpeculativeHold(
              mousePointerLayout.x.toFloat(),
              mousePointerLayout.y.toFloat(),
              fingers,
            )
          }
          MouseButton.X1_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
          // X2 ("forward") -> Recents: there is no forward-navigation concept on
          // Android, so Recents is the most useful system-wide mapping for the
          // forward side button (consistent with Back on the back button).
          MouseButton.X2_FORWARD -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
      }

      MouseEvent.Type.Up -> {
        log.debug { "Up [$event]" }
        // Side buttons fired their global action on Down; nothing to do here.
        if (event.id == MouseButton.X1_BACK || event.id == MouseButton.X2_FORWARD) return
        val armed = mouseButtonDown
        // Chording: only the button that armed the drag may end it. A release of a
        // different (non-side) button is ignored so an in-progress drag survives
        // (e.g. hold Left, press+release Right must not lift the Left drag).
        if (armed != null && event.id != armed.buttonId) {
          log.debug { "Up[${event.id}] ignored; drag armed for button ${armed.buttonId}" }
          return
        }
        mouseButtonDown = null
        val currentX = mousePointerLayout.x.toFloat()
        val currentY = mousePointerLayout.y.toFloat()
        val dragState = activeDragState
        if (dragState != null) {
          // Release the held touch (a speculative hold that may or may not have
          // become a drag) at the current pointer position.
          endDragGesture(currentX, currentY)
          return
        }
        // activeDragState == null here only if the drag was cancelled
        // (onCancelled already cleared state) or no hold was armed. Do NOT
        // synthesize a phantom tap -- the speculative-hold path handles taps via
        // endDragGesture, and a cancelled drag must not inject a stray touch.
        log.debug { "Up with no active drag state; not synthesizing a tap" }
      }

      MouseEvent.Type.Wheel -> {
        // Don't dispatch a scroll/zoom gesture while a drag is in flight: Android
        // can't merge it onto the ongoing touch and would cancel the drag.
        if (activeDragState != null) return
        log.debug { "Wheel [${event.x}, ${event.y}]" }
        // Ctrl + wheel = pinch/spread zoom; otherwise our magnitude-aware wheel.
        val controlHeld =
          keyboardManager.state.value.modifierKeys.modifierKeys
            .contains(Keyboard.ModifierKey.Control)
        if (controlHeld) {
          if (event.y > 0) spreadGesture() else pinchGesture()
        } else {
          scrollBy(event.x, event.y)
        }
      }
    }
  }

  /** Set up the pointer view and add it to the window manager. */
  private fun setupMousePointer() = showMousePointer()

  /** Show the mouse pointer overlay (idempotent). */
  private fun showMousePointer() {
    // TYPE_ACCESSIBILITY_OVERLAY needs no SYSTEM_ALERT_WINDOW / "display over
    // other apps" permission -- the accessibility-service context supplies the
    // window token -- so we must NOT gate on canDrawOverlays(): doing so silently
    // hides the cursor when that unneeded permission is missing.
    if (!mousePointerVisible) {
      windowManager.addView(mousePointerView, mousePointerLayout)
      mousePointerVisible = true
    }
  }

  /** Hide the mouse pointer overlay (idempotent; safe if never shown). */
  private fun hideMousePointer() {
    if (mousePointerVisible) {
      windowManager.removeView(mousePointerView)
      mousePointerVisible = false
    }
  }

  /**
   * Called when the service is started. This is where we set the service to be
   * sticky and return START_STICKY.
   */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return START_STICKY
  }

  val deskflowImeInfo: InputMethodInfo?
    get() =
      imeManager.inputMethodList.find {
        it.packageName.lowercase().contains("deskflow")
      }

  val isDeskflowImeEnabled: Boolean
    get() =
      imeManager.enabledInputMethodList.any { it.id == deskflowImeInfo?.id }

  private val onClipboardChanged =
    object : ClipboardManager.OnPrimaryClipChangedListener {
      override fun onPrimaryClipChanged() {
        catch({
          val clip = clipboard.primaryClip

          log.debug { "onPrimaryClipChanged(clip=$clip)" }
          if (clip == null) return@catch
          // Don't relay sensitive content (passwords from a password manager / autofill),
          // and don't echo back clips we just injected from the server inbound path (which
          // marks them EXTRA_IS_SENSITIVE). The flag lives on the clip description extras.
          val isSensitive =
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
          if (isSensitive) {
            log.debug { "Skipping clipboard sync (marked sensitive)" }
            return@catch
          }
          val clipDesc = clip.description
          val itemIdx =
            0.rangeUntil(clipDesc.mimeTypeCount).find { idx ->
              listOf(
                  ClipDescription.MIMETYPE_TEXT_PLAIN,
                  ClipDescription.MIMETYPE_TEXT_HTML,
                )
                .contains(clipDesc.getMimeType(idx))
            }

          if (itemIdx == null) {
            log.warn { "No compatible mimetypes in primary clip" }
            return@catch
          }
          val item = clip.getItemAt(itemIdx)
          val text = item.coerceToText(this@GlobalInputService).toString()
          if (text.isBlank()) {
            log.warn { "ignoring empty clipdata \"${text}\"" }
            return@catch
          }
          val clipboardData =
            ClipboardData(
              0,
              0,
              mapOf(
                ClipboardData.Format.Text to
                  ClipboardData.Variant(
                    ClipboardData.Format.Text,
                    text.toByteArray(),
                  )
              ),
            )
          serviceClient.setClipboardData(
            Bundle().apply { putSerializable("clipboardData", clipboardData) }
          )
        }) { err: Throwable ->
          log.error(err) { "unable to update clipboard" }
        }
      }
    }

  /**
   * Called when the service is connected to the system. This is where we set up
   * the cursor and fetch the home packages.
   */
  override fun onServiceConnected() {
    super.onServiceConnected()

    val imeId = deskflowImeInfo
    Log.i(TAG, "imeId=$imeId,imeEnabled=$isDeskflowImeEnabled")
    clipboard.addPrimaryClipChangedListener(this.onClipboardChanged)

    setupMousePointer()

    // Track display changes (rotation, fold) so the cached screen size used for
    // pointer clamping stays correct even inside apps that handle orientation
    // themselves (android:configChanges) and don't fire window-state changes.
    displayListener = object : DisplayManager.DisplayListener {
      override fun onDisplayChanged(displayId: Int) { cachedScreenSize = null }
      override fun onDisplayAdded(displayId: Int) {}
      override fun onDisplayRemoved(displayId: Int) {}
    }
    displayManager.registerDisplayListener(displayListener, globalInputHandler)

    sendServiceConnectionEvent<GlobalInputService>()

    fetchHomePackages()
    val pkgName = rootInActiveWindow?.packageName
    isHomeScreenActiveFlow.value =
      pkgName == null || knownHomePackages.contains(pkgName)
  }

  /**
   * All accessibility events are received here; we pay little attention to
   * them, but we do check for window state changes to determine if the home
   * screen is active.
   */
  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    log.debug { "onAccessibilityEvent: ${event?.eventType}" }
    //
    when (event?.eventType) {
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
        checkIMESetup()
      }
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
        cachedScreenSize = null // rotation / fold / app transition may change the display bounds
        val pkgName = rootInActiveWindow?.packageName
        isHomeScreenActiveFlow.value =
          pkgName == null || knownHomePackages.contains(pkgName)
      }
      else -> {
        log.debug { "Ignored event: ${event?.eventType}" }
      }
    }
  }

  override fun onInterrupt() {
    // Required override.
  }

  /**
   * Accumulated (sub-threshold) mouse-wheel delta waiting to be turned into a
   * scroll gesture. Wheel events and gesture callbacks are all dispatched on the
   * main looper ([globalInputHandler]), so these need no synchronization.
   */
  private var wheelAccumX = 0
  private var wheelAccumY = 0

  /**
   * Scroll by the mouse-wheel delta, scaled to pixels. Unlike the old fixed
   * swipe, the distance reflects the wheel magnitude, and small/slow deltas
   * accumulate instead of being dropped or collapsed into a single jump.
   * > Example: scroll a list or web page with the mouse wheel.
   */
  private fun scrollBy(deltaX: Int, deltaY: Int) {
    wheelAccumX += deltaX
    wheelAccumY += deltaY
    flushWheelScroll()
  }

  private fun flushWheelScroll() {
    if (globalInputPending) return

    val dx = wheelAccumX
    val dy = wheelAccumY
    if (dx == 0 && dy == 0) return
    wheelAccumX = 0
    wheelAccumY = 0

    val screen = screenPx().px
    val duration = (150L + 8L * (abs(dx) + abs(dy))).coerceIn(150L, 400L)

    val cx = mousePointerLayout.x.toFloat()
    val cy = mousePointerLayout.y.toFloat()
    // Magnitude (WHEEL_DELTA ±120/notch → ~16% screen/notch) and screen-edge
    // clamping live in the pure, unit-tested wheelScrollEndpoints() helper.
    val endpoints = wheelScrollEndpoints(
      dx = dx,
      dy = dy,
      cx = cx,
      cy = cy,
      screenW = screen.width.toFloat(),
      screenH = screen.height.toFloat(),
    )
    val builder = GestureDescription.Builder()

    if (dy != 0) {
      // Wheel up (dy > 0) → page UP → finger drag DOWN (content follows finger).
      val path =
        Path().apply {
          moveTo(cx, cy)
          lineTo(cx, endpoints.endY)
        }
      builder.addStroke(StrokeDescription(path, 0, duration))
    }
    if (dx != 0) {
      // Horizontal wheel/tilt: dx > 0 (tilt right) → scroll right → finger drag LEFT.
      val path =
        Path().apply {
          moveTo(cx, cy)
          lineTo(endpoints.endX, cy)
        }
      builder.addStroke(StrokeDescription(path, 0, duration))
    }

    globalInputPending = true
    log.debug { "scrollBy dx=$dx dy=$dy duration=$duration endpoints=$endpoints" }
    safeDispatch(builder.build(), gestureResultCallback, globalInputHandler)
  }

  /**
   * Fetch the list of known home packages. This is used to determine if the
   * current screen is the home screen.
   */
  private fun fetchHomePackages() {
    val intent =
      Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
    val resolveInfoList =
      packageManager.queryIntentActivities(
        intent,
        PackageManager.MATCH_DEFAULT_ONLY,
      )
    knownHomePackages.clear()
    for (info in resolveInfoList) {
      knownHomePackages.add(info.activityInfo.packageName)
    }
  }

  /**
   * Click the currently focused node. This is useful for clicking on input
   * fields or buttons that are currently focused.
   * > Example: It's used for the DPAD_CENTER action in the system actions.
   */
  private fun clickFocused() {
    val focusedNode = findFocus(FOCUS_INPUT)
    // val focusedNode =
    // rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    if (focusedNode != null) {
      logNodeHierarchy(focusedNode, 0)
      focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } else {
      log.warn { "No focused node found to click" }
    }
  }

  /// **
  // * Example: continuously move pointer in a circle.
  // * Call this from onServiceConnected or via a button in your overlay.
  // */
  // fun spinPointer(centerX: Int, centerY: Int, radius: Int, steps: Int = 36,
  // intervalMs: Long = 100) {
  //    for (i in 0 until steps) {
  //        uiHandler.postDelayed({
  //            val theta = 2 * Math.PI * i / steps
  //            val x = centerX + (radius * Math.cos(theta)).toInt()
  //            val y = centerY + (radius * Math.sin(theta)).toInt()
  //            movePointer(centerX, centerY, x - centerX, y - centerY)
  //        }, i * intervalMs)
  //    }
  // }

  companion object {
    private const val CHANNEL_ID = "deskflow_service_channel"
    private const val NOTIF_IME_NOT_SETUP_ID = 1
    private const val NOTIF_MEDIA_ACCESS_ID = 2

    /** How long (ms) the wake lock is held to turn the screen on for input. */
    private const val WAKE_ON_INPUT_MS = 3_000L

    /** Delay (ms) after a disconnect before auto-showing the system IME picker. */
    private const val IME_PICKER_DELAY_MS = 10_000L

    private val TAG = GlobalInputService::class.java.simpleName
    private val log =
      KLoggingManager.logger(GlobalInputService::class.java.simpleName)

    private fun logNodeHierarchy(nodeInfo: AccessibilityNodeInfo, depth: Int) {
      val bounds = Rect()
      nodeInfo.getBoundsInScreen(bounds)

      val sb = StringBuilder()
      if (depth > 0) {
        (0..<depth).forEach { _ -> sb.append("  ") }
        sb.append("\u2514 ")
      }
      sb.append(nodeInfo.className)
      sb.append(" (" + nodeInfo.childCount + ")")
      sb.append(" $bounds")
      if (nodeInfo.getText() != null) {
        sb.append(" - \"" + nodeInfo.getText() + "\"")
      }
      log.trace { sb.toString() }

      for (i in 0..<nodeInfo.childCount) {
        val childNode = nodeInfo.getChild(i)
        if (childNode != null) {
          logNodeHierarchy(childNode, depth + 1)
        }
      }
    }
  }
}

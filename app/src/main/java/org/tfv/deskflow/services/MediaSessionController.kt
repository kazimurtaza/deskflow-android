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

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import androidx.core.app.NotificationManagerCompat
import org.tfv.deskflow.client.input.MediaToggle
import org.tfv.deskflow.client.input.mediaToggleAction
import org.tfv.deskflow.client.util.logging.KLoggingManager

/**
 * Bridges Space→play/pause to the active media session. Reading the session list requires
 * our [MediaNotificationListener] to be an enabled notification listener; callers should
 * check [isNotificationAccessEnabled] and prompt the user otherwise.
 */
class MediaSessionController(private val ctx: Context) {

  private val mediaSessionManager by lazy {
    ctx.getSystemService(MediaSessionManager::class.java)
  }

  private val listenerComponent by lazy {
    ComponentName(ctx, MediaNotificationListener::class.java)
  }

  /** True if the user has granted Deskflow Notification access (for our listener). */
  fun isNotificationAccessEnabled(): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

  /**
   * Toggle play/pause on the highest-priority active session. Returns true if a transport
   * command was dispatched, false if there was nothing to toggle or we lack authorization.
   */
  fun togglePlayPause(): Boolean {
    val manager = mediaSessionManager ?: return false
    val sessions = try {
      manager.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
      log.warn(e) { "Not authorized for media sessions (notification access disabled?)" }
      return false
    } catch (e: Exception) {
      log.warn(e) { "Failed to query active media sessions" }
      return false
    }
    val controller = sessions.firstOrNull() ?: return false
    val state = controller.playbackState?.state
    val action = mediaToggleAction(state)
    if (action == null) {
      log.debug { "No play/pause action for ${controller.packageName} (state=$state)" }
      return false
    }
    val transport = controller.transportControls
    when (action) {
      MediaToggle.Pause -> transport.pause()
      MediaToggle.Play -> transport.play()
    }
    log.debug { "Media ${action.name.lowercase()} dispatched to ${controller.packageName}" }
    return true
  }

  companion object {
    private val log = KLoggingManager.logger(MediaSessionController::class)
  }
}

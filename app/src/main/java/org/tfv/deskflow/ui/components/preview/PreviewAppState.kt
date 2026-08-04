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

package org.tfv.deskflow.ui.components.preview

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.data.aidl.ConnectionState
import org.tfv.deskflow.data.aidl.ScreenState
import org.tfv.deskflow.data.aidl.ServerState
import org.tfv.deskflow.ext.ConnectionStateDefaults
import org.tfv.deskflow.ext.copy
import org.tfv.deskflow.logging.LogRecordEvent
import org.tfv.deskflow.ui.components.IAppState
import org.tfv.deskflow.ui.components.LocalAppState


class PreviewAppState(
  override val navController: NavHostController,
  initialPermissionsGranted: Boolean = true,
  ctx: Context
) : IAppState {

  override val editorPlaygroundFocused = MutableStateFlow(false)
  override fun setEditorPlaygroundFocused(focused: Boolean) {
    editorPlaygroundFocused.update { focused }
  }

  val previewConnectionStateFlow = MutableStateFlow(ConnectionStateDefaults(ctx))

  override val connectionStateFlow: StateFlow<ConnectionState> = previewConnectionStateFlow.asStateFlow()
  override fun setEnabled(enabled: Boolean) {
    previewConnectionStateFlow.value = previewConnectionStateFlow.value.copy(isEnabled = enabled)
    log.info { "PreviewAppState: setEnabled($enabled)" }
  }

  val previewLogRecordsFlow = MutableStateFlow(emptyList<LogRecordEvent>())
  override val logRecordsFlow: StateFlow<List<LogRecordEvent>> = previewLogRecordsFlow.asStateFlow()

  override fun clearLogRecords() {
    previewLogRecordsFlow.value = emptyList()
  }
  override fun setLogRecordForwardingLevel(level: Level) {
    log.warn { "Ignoring set log record forwarding level in preview mode" }
  }

  fun addLogRecord(logRecord: LogRecordEvent) {
    previewLogRecordsFlow.update { current ->
      when {
        current.isEmpty() -> listOf(logRecord)
        current.size > 1000 -> current.subList(1, current.size) + logRecord
        else -> current + logRecord
      }
    }
  }


  override val currentDestination: NavDestination?
    @Composable
    get() {
      return null
    }

  override val topNavOptions: NavOptions
    get() = navOptions { launchSingleTop = true }

  override fun navigateToSettings() {}

  override fun navigateToHome() {}

  val previewPermissionIMEEnabledFlow = MutableStateFlow(initialPermissionsGranted)
  override val permissionIMEEnabled =
    previewPermissionIMEEnabledFlow.asStateFlow()

  val previewPermissionAccessibilityEnabledFlow = MutableStateFlow(initialPermissionsGranted)
  override val permissionAccessibilityEnabled: StateFlow<Boolean> =
    previewPermissionAccessibilityEnabledFlow.asStateFlow()

  override fun updatePermissions(
    accessibilityEnabled: Boolean,
    imeEnabled: Boolean
  ): Boolean {
    return accessibilityEnabled && imeEnabled
  }

  companion object {
    private val log = KLoggingManager.logger<PreviewAppState>()
  }
}

@Composable
fun rememberPreviewAppState(
  navController: NavHostController = rememberNavController(),
  ctx: Context = LocalContext.current
): IAppState {

  return remember(navController) {
    PreviewAppState(navController, ctx = ctx)
  }
}


@Composable
fun PreviewAppState(
  content: @Composable (PreviewAppState) -> Unit
) {
  val previewAppState = rememberPreviewAppState()
  CompositionLocalProvider(LocalAppState provides previewAppState) {
    content(previewAppState as PreviewAppState)
  }
}

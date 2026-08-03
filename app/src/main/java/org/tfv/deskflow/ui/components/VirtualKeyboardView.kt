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

package org.tfv.deskflow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.tfv.deskflow.R
import org.tfv.deskflow.ext.drawTopShadow
import org.tfv.deskflow.ui.annotations.PreviewAll
import org.tfv.deskflow.ui.components.preview.PreviewAppState
import org.tfv.deskflow.ui.components.preview.PreviewDeskflowThemedRoot
import org.tfv.deskflow.ui.graphics.beveledTopEdgeShape
import org.tfv.deskflow.ui.theme.DeskflowTheme
import org.tfv.deskflow.ui.theme.LocalDeskflowExtendedColorScheme

//state: VirtualKeyboardViewState
@Composable
fun VirtualKeyboardView(
    appState: IAppState
) {
    val connState by appState.connectionStateFlow.collectAsStateWithLifecycle()
    val (isPortrait, isXL, isLarge) = currentDeviceConfig()
    DeskflowTheme {
        val extColorScheme = LocalDeskflowExtendedColorScheme.current
        Surface(
            modifier = Modifier.height(60.dp)
                .fillMaxWidth(),
            tonalElevation = 6.dp,
            color = extColorScheme.keyboardBackground,
            shadowElevation = 24.dp,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides extColorScheme.onKeyboardBackground
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                        .drawTopShadow(shadowHeight = 16.dp)
                        .padding(start = 32.dp,end = 32.dp, top = 8.dp, bottom = 8.dp)

                ) {
                    Image(
                        painter = painterResource(id = R.drawable.deskflow_icon_fit),
                        contentDescription = stringResource(R.string.app_toolbar_logo_desc),
                        modifier = Modifier.height(24.dp),
                    )
                    val serverHostAndPort =
                        connState.screen.run {
                            val suffix = if (server.useTls) " TLS" else ""
                            val host = "${server.address}:${server.port}${suffix}"

                            "$name\n$host"
                        }
                    Text(
                        textAlign = TextAlign.Start,
                        text = serverHostAndPort,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    ConnectionStatusLabel(connState)

                    // IME Picker Button
                    val context = LocalContext.current
                    IconButton(
                        onClick = {
                            // Show IME picker
                            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.showInputMethodPicker()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.keyboard_picker_button_desc),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@PreviewAll
@Composable
fun VirtualKeyboardViewPreview() {
    PreviewDeskflowThemedRoot { appState -> VirtualKeyboardView(appState) }

}

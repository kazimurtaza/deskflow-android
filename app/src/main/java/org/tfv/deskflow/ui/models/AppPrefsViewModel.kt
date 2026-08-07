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

package org.tfv.deskflow.ui.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.tfv.deskflow.data.appPrefsStore
import org.tfv.deskflow.data.models.AppPrefs
import org.tfv.deskflow.data.models.AppPrefs.ActionsConfig
import org.tfv.deskflow.data.models.AppPrefs.ScreenConfig
import org.tfv.deskflow.data.models.copy


fun newDefaultAppPrefs(): AppPrefs {
    return AppPrefs.getDefaultInstance().copy {
        screen = screen.copy {
            name = "Android Screen"
            server = server.copy {
                address = "localhost"
                port = 24800
                useTls = true
            }
        }
        actions = actions.copy {
            shortcuts.clear()
        }
    }
}

class AppPrefsViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = application.applicationContext.appPrefsStore

    val appPrefs = dataStore.data
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly,
            newDefaultAppPrefs())

    fun resetActionShortcuts() {
        viewModelScope.launch {
            dataStore.updateData {
                it.copy {
                    actions = actions.copy {
                        this.shortcuts.clear()
                    }
                }
            }
        }
    }

    fun setActionShortcut(shortcut: ActionsConfig.Shortcut) {
        viewModelScope.launch {
            dataStore.updateData {
                it.copy {
                    actions = actions.copy {
                        shortcuts[shortcut.actionId] = shortcut
                    }
                }
            }
        }
    }

    fun update(appPrefs: AppPrefs) {
        viewModelScope.launch {
            dataStore.updateData {
                if (it == appPrefs || appPrefs == newDefaultAppPrefs())
                    return@updateData it

                it.toBuilder().mergeFrom(appPrefs)
                    .build()
            }
        }
    }

    fun updateScreenConfig(screenConfig: ScreenConfig) {
        updateScreenConfig(screenConfig.name, screenConfig.server)
    }

    fun updateScreenConfig(name: String, server: ScreenConfig.ServerConfig) {
        viewModelScope.launch {
            dataStore.updateData {
                it.copy {
                    screen = screen.copy {
                        this.name = name
                        this.server = server
                    }
                }
            }
        }
    }
}
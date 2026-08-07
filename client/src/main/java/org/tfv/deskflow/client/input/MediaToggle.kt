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
package org.tfv.deskflow.client.input

/** Whether a play/pause toggle should resume or pause the active media session. */
enum class MediaToggle { Pause, Play }

// State values mirror android.media.session.PlaybackState (kept as plain Ints so the
// decision is pure and JVM-unit-testable, with no Android dependency in this module).
private const val STATE_STOPPED = 1
private const val STATE_PAUSED = 2
private const val STATE_PLAYING = 3
private const val STATE_FAST_FORWARDING = 4
private const val STATE_REWINDING = 5
private const val STATE_BUFFERING = 6

/**
 * Decide whether a play/pause toggle should [MediaToggle.Pause] or [MediaToggle.Play] an
 * active session given its current [playbackState] (the raw
 * `PlaybackState.getState()` value, or null). Returns null when there is nothing to act on
 * (no/unknown/error/connecting/skipping state), so the caller can fall through instead of
 * issuing a no-op transport command.
 *
 * Pure (no Android types) so it is JVM-unit-testable.
 */
fun mediaToggleAction(playbackState: Int?): MediaToggle? = when (playbackState) {
  STATE_PLAYING, STATE_FAST_FORWARDING, STATE_REWINDING -> MediaToggle.Pause
  STATE_PAUSED, STATE_BUFFERING, STATE_STOPPED -> MediaToggle.Play
  else -> null // STATE_NONE (0), STATE_CONNECTING, STATE_ERROR, skipping states, or null
}

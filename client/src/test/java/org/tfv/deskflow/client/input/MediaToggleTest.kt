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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MediaToggleTest {

  // Values mirror android.media.session.PlaybackState.
  private val playing = 3
  private val paused = 2
  private val buffering = 6
  private val stopped = 1
  private val fastForwarding = 4
  private val rewinding = 5
  private val none = 0
  private val error = 7
  private val connecting = 8
  private val skippingToNext = 10

  @Test
  fun playingPauses() {
    assertEquals(MediaToggle.Pause, mediaToggleAction(playing))
  }

  @Test
  fun fastForwardingAndRewindingPause() {
    // These are "actively playing" states; a toggle should pause.
    assertEquals(MediaToggle.Pause, mediaToggleAction(fastForwarding))
    assertEquals(MediaToggle.Pause, mediaToggleAction(rewinding))
  }

  @Test
  fun pausedPlays() {
    assertEquals(MediaToggle.Play, mediaToggleAction(paused))
  }

  @Test
  fun bufferingPlays() {
    assertEquals(MediaToggle.Play, mediaToggleAction(buffering))
  }

  @Test
  fun stoppedPlays() {
    assertEquals(MediaToggle.Play, mediaToggleAction(stopped))
  }

  @Test
  fun nonActionableStatesReturnNull() {
    assertNull(mediaToggleAction(none))
    assertNull(mediaToggleAction(error))
    assertNull(mediaToggleAction(connecting))
    assertNull(mediaToggleAction(skippingToNext))
  }

  @Test
  fun nullStateReturnsNull() {
    assertNull(mediaToggleAction(null))
  }
}

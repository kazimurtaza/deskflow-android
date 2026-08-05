/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
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

class WheelScrollMathTest {

  // Reference: 1080×2400 screen, cursor centered. pxPerUnit = 2400*0.16/120 = 3.2 → one notch = 384px (16% of 2400).
  private val sw = 1080f
  private val sh = 2400f
  private val cx = 540f
  private val cy = 1200f
  private val delta = 0.001f

  @Test
  fun verticalMagnitudeIsSixteenPercentPerNotch() {
    // one notch up (dy=+120) → drag DOWN by 16% of screenH
    assertEquals(cy + sh * 0.16f, wheelScrollEndpoints(0, 120, cx, cy, sw, sh).endY, delta)
    // one notch down (dy=-120) → drag UP by 16% of screenH
    assertEquals(cy - sh * 0.16f, wheelScrollEndpoints(0, -120, cx, cy, sw, sh).endY, delta)
  }

  @Test
  fun verticalDirectionSigns() {
    assertTrue(wheelScrollEndpoints(0, 120, cx, cy, sw, sh).endY > cy)
    assertTrue(wheelScrollEndpoints(0, -120, cx, cy, sw, sh).endY < cy)
  }

  @Test
  fun horizontalTiltDirection() {
    // dx>0 (tilt right) → drag LEFT (endX<cx); dx<0 → drag RIGHT
    assertTrue(wheelScrollEndpoints(120, 0, cx, cy, sw, sh).endX < cx)
    assertTrue(wheelScrollEndpoints(-120, 0, cx, cy, sw, sh).endX > cx)
  }

  @Test
  fun clampTopEdge() {
    // cursor near top, scroll down (drag up) → clamps to 0 instead of going negative
    assertEquals(0f, wheelScrollEndpoints(0, -120, cx, 50f, sw, sh).endY, delta)
  }

  @Test
  fun clampBottomEdge() {
    assertEquals(sh, wheelScrollEndpoints(0, 120, cx, sh - 10f, sw, sh).endY, delta)
  }

  @Test
  fun clampLeftEdge() {
    // cursor near left, tilt right (drag left) → clamps to 0
    assertEquals(0f, wheelScrollEndpoints(120, 0, 10f, cy, sw, sh).endX, delta)
  }

  @Test
  fun clampRightEdge() {
    assertEquals(sw, wheelScrollEndpoints(-120, 0, sw - 10f, cy, sw, sh).endX, delta)
  }

  @Test
  fun zeroDeltaLeavesCursorUnchanged() {
    val e = wheelScrollEndpoints(0, 0, cx, cy, sw, sh)
    assertEquals(cx, e.endX, delta)
    assertEquals(cy, e.endY, delta)
  }

  @Test
  fun pureVerticalLeavesX() {
    assertEquals(cx, wheelScrollEndpoints(0, 120, cx, cy, sw, sh).endX, delta)
  }

  @Test
  fun pureHorizontalLeavesY() {
    assertEquals(cy, wheelScrollEndpoints(120, 0, cx, cy, sw, sh).endY, delta)
  }
}

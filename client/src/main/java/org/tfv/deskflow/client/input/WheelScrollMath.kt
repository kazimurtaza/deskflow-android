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

/** Mouse-wheel delta per notch (Windows WHEEL_DELTA); the Barrier/Deskflow "DMWM" wire unit (server sends ±120/notch). */
private const val WHEEL_DELTA = 120

/** Screen fraction traveled per wheel notch (~16% of the screen height). */
private const val SCROLL_FRACTION_PER_NOTCH = 0.16f

/** Clamped drag-stroke endpoints for an accumulated wheel delta (no Android types). */
data class WheelStrokeEndpoints(val endX: Float, val endY: Float)

/**
 * Compute the clamped drag-stroke endpoints for accumulated wheel deltas
 * [dx] (horizontal tilt) and [dy] (vertical) from cursor ([cx], [cy]) on a
 * [screenW]×[screenH] screen. Pure (no Android types) so it is JVM-unit-testable.
 *
 * Sign mapping (matches GlobalInputService):
 *  - dy > 0 (wheel up)   → endY > cy  (finger drag DOWN → scrolls the page UP)
 *  - dx > 0 (tilt right) → endX < cx  (finger drag LEFT → scrolls right)
 *
 * One notch (±120) moves ~16% of the screen on the given axis. Endpoints are
 * clamped to [0, dim] so GestureDescription.StrokeDescription never throws
 * "Path bounds must not be negative" near an edge. If an axis delta is 0 the
 * corresponding endpoint equals the cursor position.
 */
fun wheelScrollEndpoints(
  dx: Int,
  dy: Int,
  cx: Float,
  cy: Float,
  screenW: Float,
  screenH: Float,
): WheelStrokeEndpoints {
  // pxPerUnit is height-based (keeps the original GlobalInputService tuning) and
  // applied to both axes: dy=±120 → ±16% of screenH, dx=±120 → ±16% of screenH.
  val pxPerUnit = screenH * SCROLL_FRACTION_PER_NOTCH / WHEEL_DELTA
  val endY = (cy + dy * pxPerUnit).coerceIn(0f, screenH)
  val endX = (cx - dx * pxPerUnit).coerceIn(0f, screenW)
  return WheelStrokeEndpoints(endX = endX, endY = endY)
}

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
package org.tfv.deskflow.types

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.tfv.deskflow.client.util.Keyboard

/**
 * Locks down [ShortcutKey.parseShortcut] (the core of the global-shortcut feature): modifier
 * parsing, special-key recognition, the uppercase→lowercase case-fold, and that a parsed
 * default matches the live-built [ShortcutKey] used in dispatch.
 */
class ShortcutKeyTest {

  @Test
  fun bareSpecialKey() {
    val sk = ShortcutKey.parseShortcut("Escape")
    assertEquals(Keyboard.SpecialKey.Escape.code, sk.keyCode)
    assertTrue(sk.isSpecial)
    assertTrue(sk.modifierKeys.modifierKeys.isEmpty())
  }

  @Test
  fun controlPlusLetter() {
    val sk = ShortcutKey.parseShortcut("Control+a")
    assertEquals('a'.code, sk.keyCode)
    assertTrue(sk.modifierKeys.modifierKeys.contains(Keyboard.ModifierKey.Control))
    assertFalse(sk.isSpecial)
  }

  @Test
  fun superModifier() {
    val sk = ShortcutKey.parseShortcut("Super+h")
    assertEquals('h'.code, sk.keyCode)
    assertTrue(sk.modifierKeys.modifierKeys.contains(Keyboard.ModifierKey.Super))
  }

  @Test
  fun metaModifier() {
    val sk = ShortcutKey.parseShortcut("Meta+h")
    assertEquals('h'.code, sk.keyCode)
    assertTrue(sk.modifierKeys.modifierKeys.contains(Keyboard.ModifierKey.Meta))
  }

  @Test
  fun caseFoldCapitalLetter() {
    // "Control+A" and "Control+a" must be the same shortcut (keyCode case-folded to lowercase).
    val upper = ShortcutKey.parseShortcut("Control+A")
    val lower = ShortcutKey.parseShortcut("Control+a")
    assertEquals('a'.code, upper.adjustedKeyCode)
    assertEquals(upper, lower)
    assertEquals(upper.hashCode(), lower.hashCode())
  }

  @Test
  fun emptyLabelThrows() {
    assertThrows<IllegalArgumentException> { ShortcutKey.parseShortcut("") }
  }

  @Test
  fun modifierOnlyThrows() {
    // A single modifier with no key is not a valid shortcut.
    assertThrows<IllegalArgumentException> { ShortcutKey.parseShortcut("Control") }
  }
}

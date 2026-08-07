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

import kotlinx.serialization.Serializable
import org.tfv.deskflow.client.util.Bitmask
import org.tfv.deskflow.client.util.Keyboard
import org.tfv.deskflow.client.util.Keyboard.findModifierKey
import org.tfv.deskflow.client.util.Keyboard.findSpecialKey

@Serializable
data class ModifierKeys(val modifierKeys: Set<Keyboard.ModifierKey> = setOf()) {
  companion object {
    private val MODIFIER_KEYS_TO_ID = arrayOf(
      Keyboard.ModifierKeyMask.Super,
      Keyboard.ModifierKeyMask.Meta,
      Keyboard.ModifierKeyMask.Shift,
      Keyboard.ModifierKeyMask.Alt,
      Keyboard.ModifierKeyMask.Control,
    )
  }

  private fun hasModifier(mask: Keyboard.ModifierKeyMask): Boolean {
    return modifierKeys.any { it.mask == mask }
  }

  private fun modifierIdPart(mask: Keyboard.ModifierKeyMask): String {
    return when (hasModifier(mask)) {
      true -> mask.name.lowercase()
      false -> "none"
    }
  }

  fun updateModifierKeys(pressed: Boolean, vararg changedModifierKeys: Keyboard.ModifierKey): ModifierKeys {
    val updatedModifierKeys = modifierKeys.toMutableSet()
    changedModifierKeys.forEach {
      if (pressed && !updatedModifierKeys.contains(it)) {
        updatedModifierKeys.add(it)
      } else if (!pressed && updatedModifierKeys.contains(it)) {
        updatedModifierKeys.remove(it)
      }
    }
    return copy(modifierKeys = updatedModifierKeys)
  }

  val hasSuper: Boolean
    get() = hasModifier(Keyboard.ModifierKeyMask.Super)

  val hasMeta: Boolean
    get() = hasModifier(Keyboard.ModifierKeyMask.Meta)

  val hasShift: Boolean
    get() = hasModifier(Keyboard.ModifierKeyMask.Shift)

  val hasAlt: Boolean
    get() = hasModifier(Keyboard.ModifierKeyMask.Alt)

  val hasControl: Boolean
    get() = hasModifier(Keyboard.ModifierKeyMask.Control)

  val mask: Bitmask
    get() {
      var bitmask = Bitmask()
      for (modifierKey in modifierKeys) {
        bitmask = bitmask.set(modifierKey.mask.bit)
      }
      return bitmask
    }

  val id: String = MODIFIER_KEYS_TO_ID.joinToString("-") { modifierIdPart(it) }

  val label: String
    get() = modifierKeys.joinToString("+") { it.mask.label }

  override fun equals(other: Any?): Boolean {
    return other is ModifierKeys && other.id == this.id
  }

  override fun hashCode(): Int {
    return id.hashCode()
  }
}

@Serializable
data class ShortcutKey(
  val keyCode: Int,
  val modifierKeys: ModifierKeys = ModifierKeys()
) {

  constructor(label: String, args: Pair<Int,
    ModifierKeys> = parseShortcutArgs(label)) : this(args.first, args.second)

  val specialKey = Keyboard.findSpecialKey(keyCode)
  val isSpecial = specialKey != null
  val adjustedKeyCode = if (!isSpecial && keyCode.toChar().isUpperCase())  keyCode + 0x20 else keyCode

  val keyLabel: String = when (specialKey != null) {
    true -> specialKey.label
    false -> adjustedKeyCode.toChar().toString()
  }

  val modifierLabel: String
    get() = modifierKeys.label

  val id: String = arrayOf(modifierKeys.id, keyLabel.lowercase()).joinToString("+")

  val label: String
    get() = arrayOf(modifierLabel, keyLabel).filter { it.isNotBlank() }.joinToString(separator = "+")

  override fun equals(other: Any?): Boolean {
    return other is ShortcutKey && other.id == this.id
  }

  override fun hashCode(): Int {
    return this.id.hashCode()
  }

  companion object {
    fun parseShortcut(label: String): ShortcutKey {
      return ShortcutKey(label)
    }

    fun parseShortcutArgs(label: String): Pair<Int, ModifierKeys> {
      val parts = label.split("+").filter { it.isNotBlank()}.toMutableList()
      require(parts.isNotEmpty()) { "label contains no keys" }

      if (parts.size == 1) {
        val specialKey = findSpecialKey(parts[0])
        if (specialKey != null) {
          return Pair(specialKey.code, ModifierKeys())
        }
      }

      require(parts.size >= 2) {
        "label contains < 2 parts, at least 1 modifier and 1 special or regular key is required"
      }

      val keyCodeLabel = parts.removeAt(parts.lastIndex)
      val modifierKeySet = parts.map {
        findModifierKey(it) ?:
          throw Error("Unable to map $it to a valid modifier key")
      }.toSet()
      require(findModifierKey(keyCodeLabel) == null) {
        "Shortcut must end with either a special key or a standard alphanumeric key, it can not be a modifier"
      }
      val specialKey = Keyboard.SpecialKey.entries.find { it.name.lowercase() == keyCodeLabel.lowercase() }
      val keyCode = when (specialKey != null) {
        true -> specialKey.code
        false -> {
          require(keyCodeLabel.length == 1) {
            "Key code must be exactly 1 alphanumeric character"
          }
          val keyCodeChar:Char = keyCodeLabel.lowercase()[0]
          keyCodeChar.code
        }
      }

      return Pair(keyCode, ModifierKeys(modifierKeySet))
    }
  }
}


interface KeyboardAction<ID> {
  val id: ID
  val shortcutKeys: List<ShortcutKey>
  val defaultShortcutKeys: List<ShortcutKey>
  val label: String
}

@Serializable
data class GlobalKeyboardAction(
  override val id: String,
  override val shortcutKeys: List<ShortcutKey>,
  val actionId: Int,
  override val label: String,
  override val defaultShortcutKeys: List<ShortcutKey> = emptyList(),
  val ignoreIME: Boolean = false
) : KeyboardAction<String>

@Serializable
data class EditorKeyboardAction(
  override val id: String,
  override val shortcutKeys: List<ShortcutKey>,
  val actionId: Int,
  override val label: String,
  val specialKey: Keyboard.SpecialKey? = null,
  override val defaultShortcutKeys: List<ShortcutKey> = emptyList()
) : KeyboardAction<String>


@Suppress("unused")
@Serializable
data class KeyboardManagerState<ID, T: KeyboardAction<ID>>(
  val modifierKeys: ModifierKeys = ModifierKeys(),
  val keyboardActions: Map<ID, T> = mapOf(),
) {

  fun changeModifierKeys(pressed: Boolean, vararg changedModifierKeys: Keyboard.ModifierKey): KeyboardManagerState<ID, T> {
    return copy(modifierKeys = modifierKeys.updateModifierKeys(pressed, changedModifierKeys = changedModifierKeys))
  }

  fun setKeyboardActions(keyboardActions: Set<T>): KeyboardManagerState<ID, T> {
    val kbEntries = keyboardActions.map { Pair(it.id,it)}.toTypedArray()
    return copy(keyboardActions = mutableMapOf(*kbEntries))
  }

  fun addKeyboardActions(vararg newKeyboardActions: T, overwrite: Boolean = false): KeyboardManagerState<ID,T> {
    val updatedKeyboardActions = keyboardActions.toMutableMap()

    val (duplicateKeyboardActions, uniqueKeyboardActions) = newKeyboardActions.partition { keyboardActions.contains(it.id)}
    val changedKeyboardActions = when {
      !overwrite -> {
        require(duplicateKeyboardActions.isEmpty()) {
          "Keyboard actions are already registered"
        }
        uniqueKeyboardActions
      }
      else -> {
        duplicateKeyboardActions.forEach {
          updatedKeyboardActions.remove(it.id)
        }
        newKeyboardActions.toList()
      }
    }

    changedKeyboardActions.forEach {
      updatedKeyboardActions[it.id] = it
    }

    return copy(keyboardActions = updatedKeyboardActions)
  }
}

typealias GlobalKeyboardManagerState = KeyboardManagerState<String, GlobalKeyboardAction>

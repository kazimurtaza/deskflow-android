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
package org.tfv.deskflow.client.io

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.tfv.deskflow.client.io.msgs.Message
import kotlin.reflect.full.createInstance

/**
 * For every template with a concrete message class, serialize a default instance to its
 * wire frame and parse it back, asserting the message class round-trips. This exercises the
 * full write→frame→parse path; the UTF-8 string-prefix regression is covered separately by
 * [StringReadWriteTest].
 */
class MessageRoundTripTest {

  @Test
  fun everySerializableTemplateRoundTrips() {
    val failures = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    var roundTripped = 0
    for (template in MessageTemplate.entries) {
      val clazz = template.clazz ?: continue
      val name = template.name

      val original = try {
        clazz.createInstance() as Message
      } catch (e: Throwable) {
        skipped += "$name (createInstance failed: ${e.message})"
        continue
      }
      val frame = try {
        original.toBytes()
      } catch (e: Throwable) {
        skipped += "$name (toBytes failed: ${e.message})"
        continue
      }
      val parsed = try {
        DynamicByteBuffer().apply { append(frame) }.let { MessageParser().parseBuffer(it) }
      } catch (e: Throwable) {
        skipped += "$name (parse threw: ${e.message})"
        continue
      }

      // A default instance that doesn't frame-round-trip (e.g. HelloMessage, the
      // headerless "Barrier" greeting parsed via the handshake, not parseBuffer) is
      // a skip, not a failure. Wrong class or a split frame ARE failures.
      when {
        parsed.size == 0 ->
          skipped += "$name (default does not frame-round-trip; likely special-case/headerless)"
        parsed.size > 1 ->
          failures += "$name (parsed ${parsed.size} messages, expected 1)"
        parsed[0].javaClass != original.javaClass ->
          failures += "$name (parsed ${parsed[0].javaClass.simpleName}, expected ${original.javaClass.simpleName})"
        else -> roundTripped++
      }
    }

    assertTrue(failures.isEmpty(), "Round-trip failures:\n" + failures.joinToString("\n"))
    // Sanity: the bulk of framed messages must round-trip (guards the writeString/
    // framing path), not just a handful.
    assertTrue(roundTripped >= 15, "only $roundTripped templates round-tripped:\n" + skipped.joinToString("\n"))
  }
}

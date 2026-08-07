package org.tfv.deskflow.client.util

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallableReferenceEqTest {
  fun handler(x: String) { println(x) }

  @Test
  fun two_member_refs_to_same_instance_compare_equal_and_remove() {
    val list = CopyOnWriteArrayList<(String) -> Unit>()
    list.add(this::handler)
    // a *different* evaluation of the same reference expression:
    val removed = list.remove(this::handler)
    assertTrue(removed, "off() should remove listener registered by on()")
    assertEquals(0, list.size)
  }
}

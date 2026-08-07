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
package org.tfv.deskflow.client.net.tls

import java.io.InputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Locks down the TOFU trust model in [FingerprintTrustManager] (stage-on-first-use, persist
 * only after handshake commit, reject mismatches) and the [DeskflowFingerprint] rendering the
 * user compares against the server GUI.
 */
class TrustTofuTest {

  private fun loadCert(): X509Certificate {
    val stream: InputStream = checkNotNull(
      TrustTofuTest::class.java.getResourceAsStream("/test-cert.pem")
    ) { "test-cert.pem not on classpath" }
    return stream.use {
      CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
  }

  private class FakeStore : ServerTrustStore {
    val pinned = mutableMapOf<String, String>()
    var pinCallCount = 0
    override fun pinnedFingerprint(host: String): String? = pinned[host]
    override fun pin(host: String, sha256HexLower: String) {
      pinned[host] = sha256HexLower
      pinCallCount++
    }
    override fun clear(host: String) { pinned.remove(host) }
  }

  @Test
  fun firstConnectStagesButDoesNotPin() {
    val store = FakeStore()
    FingerprintTrustManager("host", store).checkServerTrusted(arrayOf(loadCert()), "RSA")
    assertEquals(0, store.pinCallCount, "first checkServerTrusted must not persist")
    assertNull(store.pinnedFingerprint("host"))
  }

  @Test
  fun commitPinPersistsStagedFingerprint() {
    val store = FakeStore()
    val mgr = FingerprintTrustManager("host", store)
    val cert = loadCert()
    mgr.checkServerTrusted(arrayOf(cert), "RSA")
    mgr.commitPin()
    assertEquals(DeskflowFingerprint.sha256HexLower(cert), store.pinnedFingerprint("host"))
    assertEquals(1, store.pinCallCount)
  }

  @Test
  fun commitPinWithoutStagedCandidateIsNoOp() {
    val store = FakeStore()
    FingerprintTrustManager("host", store).commitPin()
    assertEquals(0, store.pinCallCount)
  }

  @Test
  fun matchingPinnedFingerprintDoesNotThrowOrRePin() {
    val cert = loadCert()
    val store = FakeStore().apply { pinned["host"] = DeskflowFingerprint.sha256HexLower(cert) }
    val mgr = FingerprintTrustManager("host", store)
    mgr.checkServerTrusted(arrayOf(cert), "RSA")
    assertEquals(0, store.pinCallCount, "a matching pin must not re-pin")
  }

  @Test
  fun mismatchingPinnedFingerprintThrowsAndLeavesStoreUnchanged() {
    val bogus = "00".repeat(32)
    val store = FakeStore().apply { pinned["host"] = bogus }
    val mgr = FingerprintTrustManager("host", store)
    assertThrows<CertificateException> {
      mgr.checkServerTrusted(arrayOf(loadCert()), "RSA")
    }
    assertEquals(bogus, store.pinnedFingerprint("host"))
  }

  @Test
  fun emptyChainThrows() {
    val mgr = FingerprintTrustManager("host", FakeStore())
    assertThrows<IllegalArgumentException> {
      mgr.checkServerTrusted(emptyArray(), "RSA")
    }
  }

  @Test
  fun fingerprintRenderingGoldenValues() {
    val hex = "0123456789abcdef".repeat(4) // 64 lowercase hex chars (32 bytes)
    val group = "01:23:45:67:89:AB:CD:EF"
    // colonHexUpper wraps at ~24 chars/line (8 groups), joined by newline.
    assertEquals(List(4) { group }.joinToString("\n"), DeskflowFingerprint.colonHexUpper(hex))
    assertEquals("v2:sha256:$hex", DeskflowFingerprint.toDbLine(hex))
  }
}

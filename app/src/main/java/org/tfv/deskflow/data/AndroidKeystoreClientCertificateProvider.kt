/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the software is
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

package org.tfv.deskflow.data

import java.math.BigInteger
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.security.auth.x500.X500Principal
import org.tfv.deskflow.client.net.tls.ClientCertificateProvider
import org.tfv.deskflow.client.net.tls.DeskflowFingerprint

/**
 * Provides an auto-generated, self-signed RSA-2048 client certificate for mutual
 * TLS (Deskflow `PeerAuth`), mirroring the desktop client which always loads its
 * own cert and only presents it when the server requests one.
 *
 * The key + self-signed certificate live in the [AndroidKeyStore] (hardware-backed
 * where available); the private key never enters app memory. On first use the
 * certificate is generated (CN=Deskflow, SHA-256, serial 1, ~10y validity) and
 * reused thereafter. Callers can read [fingerprintColonHex] to display this
 * device's fingerprint so the user can register it on a PeerAuth server's
 * `trusted-clients`.
 */
class AndroidKeystoreClientCertificateProvider : ClientCertificateProvider {

  private val keyStore: KeyStore by lazy {
    KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
  }

  /** Ensure a client certificate exists under [ALIAS], generating one if absent. */
  @Synchronized
  fun ensureCertificateExists() {
    if (keyStore.containsAlias(ALIAS)) return
    val now = System.currentTimeMillis()
    val spec =
      KeyGenParameterSpec.Builder(
          ALIAS,
          KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
        .setKeySize(2048)
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
        .setCertificateSubject(X500Principal("CN=Deskflow"))
        // AndroidKeyStore self-signs the generated cert; the issuer is derived
        // from the subject (issuer == subject == CN=Deskflow) automatically.
        .setCertificateSerialNumber(BigInteger.ONE)
        .setCertificateNotBefore(Date(now))
        .setCertificateNotAfter(Date(now + TEN_YEARS_MILLIS))
        .build()
    val generator =
      KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
    generator.initialize(spec)
    generator.generateKeyPair()
  }

  /** The client certificate (generating if necessary), or null if unavailable. */
  fun clientCertificate(): X509Certificate? {
    return try {
      ensureCertificateExists()
      keyStore.getCertificate(ALIAS) as? X509Certificate
    } catch (e: Exception) {
      null
    }
  }

  /** This device's client-certificate fingerprint in desktop GUI format, for display. */
  fun fingerprintColonHex(): String? =
    clientCertificate()?.let {
      DeskflowFingerprint.colonHexUpper(DeskflowFingerprint.sha256HexLower(it))
    }

  /**
   * The fingerprint only if a cert already exists (does NOT generate one). Safe
   * to call from the UI / off the connect path (no keygen, no main-thread work).
   * Returns null until the client cert has been created (which happens on the
   * first TLS connection).
   */
  fun fingerprintIfPresent(): String? {
    return try {
      if (!keyStore.containsAlias(ALIAS)) null
      else
        (keyStore.getCertificate(ALIAS) as? X509Certificate)?.let {
          DeskflowFingerprint.colonHexUpper(DeskflowFingerprint.sha256HexLower(it))
        }
    } catch (e: Exception) {
      null
    }
  }

  override fun keyManagers(): Array<KeyManager>? =
    try {
      ensureCertificateExists()
      val factory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
      factory.init(keyStore, null)
      factory.keyManagers
    } catch (e: Exception) {
      null
    }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "deskflow-client-cert"
    private const val TEN_YEARS_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000
  }
}

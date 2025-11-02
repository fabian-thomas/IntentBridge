package de.fabianthomas.intentbridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object TlsIdentityStore {
    private const val TAG = "IntentBridgeTls"
    private const val PREF_NAME = "tls_identity"
    private const val KEY_PRIVATE = "identity_private"
    private const val KEY_CERT = "identity_certificate"
    private const val KEY_PEER_CERT = "peer_certificate"
    private const val KEY_PEER_FINGERPRINT = "peer_fingerprint"
    private const val KEY_AUTH_ERROR = "auth_error"
    private val secureRandom = SecureRandom()
    private val lock = Any()

    data class Identity(
        val certificate: X509Certificate,
        val fingerprint: String
    )

    fun ensureIdentity(context: Context): Identity = synchronized(lock) {
        val appContext = context.applicationContext
        val prefs = preferences(appContext)
        if (!prefs.contains(KEY_PRIVATE) || !prefs.contains(KEY_CERT)) {
            generateAndStoreIdentity(appContext)
        }
        val certificate = loadCertificate(prefs)
        Identity(certificate, fingerprintFor(certificate))
    }

    fun regenerateIdentity(context: Context): Identity = synchronized(lock) {
        val appContext = context.applicationContext
        generateAndStoreIdentity(appContext)
        clearPinnedPeerCertificate(appContext)
        clearAuthError(appContext)
        val certificate = loadCertificate(preferences(appContext))
        Identity(certificate, fingerprintFor(certificate))
    }

    fun keyManagers(context: Context): Array<KeyManager> {
        val appContext = context.applicationContext
        ensureIdentity(appContext)
        val prefs = preferences(appContext)
        val privateKey = loadPrivateKey(prefs)
        val certificate = loadCertificate(prefs)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        keyStore.setEntry(
            "identity",
            KeyStore.PrivateKeyEntry(privateKey, arrayOf(certificate)),
            KeyStore.PasswordProtection(CharArray(0))
        )
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, CharArray(0))
        return factory.keyManagers
    }

    fun trustManagers(context: Context): Array<TrustManager> = arrayOf(TofuTrustManager(context.applicationContext))

    fun localFingerprint(context: Context): String = ensureIdentity(context).fingerprint

    fun pinnedPeerFingerprint(context: Context): String? =
        preferences(context).getString(KEY_PEER_FINGERPRINT, null)

    fun pinnedPeerCertificate(context: Context): X509Certificate? {
        val bytes = preferences(context).getBase64(KEY_PEER_CERT) ?: return null
        return runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        }.getOrNull()
    }

    fun pinPeerCertificate(context: Context, certificate: X509Certificate) {
        val prefs = preferences(context)
        synchronized(lock) {
            if (prefs.contains(KEY_PEER_CERT)) return
            prefs.update {
                putString(KEY_PEER_CERT, certificate.encoded.toBase64String())
                putString(KEY_PEER_FINGERPRINT, fingerprintFor(certificate))
                remove(KEY_AUTH_ERROR)
            }
        }
    }

    fun clearPinnedPeerCertificate(context: Context) {
        preferences(context).update {
            remove(KEY_PEER_CERT)
            remove(KEY_PEER_FINGERPRINT)
            remove(KEY_AUTH_ERROR)
        }
    }

    fun markAuthError(context: Context) {
        preferences(context).update { putBoolean(KEY_AUTH_ERROR, true) }
    }

    fun clearAuthError(context: Context) {
        preferences(context).update { remove(KEY_AUTH_ERROR) }
    }

    fun hasAuthError(context: Context): Boolean =
        preferences(context).getBoolean(KEY_AUTH_ERROR, false)

    private fun generateAndStoreIdentity(context: Context) {
        val keyPair = generateKeyPair()
        val roleName = ProfileRoleStore.describe(ProfileRoleStore.getRole(context))
        val certificate = createCertificate(keyPair, roleName)
        preferences(context).update {
            putString(KEY_PRIVATE, keyPair.private.encoded.toBase64String())
            putString(KEY_CERT, certificate.encoded.toBase64String())
        }
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256, secureRandom)
        return generator.generateKeyPair()
    }

    private fun createCertificate(keyPair: KeyPair, commonName: String): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000)
        val notAfter = Date(now + 100L * 365 * 24 * 60 * 60 * 1000) // 100 years
        val serial = BigInteger(160, secureRandom)
        val subject = X500Name("CN=$commonName")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA")
            .build(keyPair.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter()
            .getCertificate(holder)
    }

    private fun loadPrivateKey(prefs: SharedPreferences): PrivateKey {
        val spec = PKCS8EncodedKeySpec(prefs.requireBase64(KEY_PRIVATE))
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(spec)
    }

    private fun loadCertificate(prefs: SharedPreferences): X509Certificate {
        val bytes = prefs.requireBase64(KEY_CERT)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun localCertificate(context: Context): X509Certificate {
        return loadCertificate(preferences(context))
    }

    private fun fingerprintFor(certificate: X509Certificate): String =
        certificate.publicKey.encoded.toFingerprint()

    private fun ByteArray.toFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString(":") { String.format("%02X", it) }
    }

    private class TofuTrustManager(private val context: Context) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            handleChain(chain)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            handleChain(chain)
        }

        private fun handleChain(chain: Array<X509Certificate>) {
            if (chain.isEmpty()) throw CertificateException("empty certificate chain")
            val cert = chain[0]
            cert.checkValidity()
            val localFingerprint = fingerprintFor(localCertificate(context))
            val incomingFingerprint = fingerprintFor(cert)
            if (incomingFingerprint == localFingerprint) {
                Log.i(TAG, "Ignoring self certificate during TOFU pinning")
                clearAuthError(context)
                return
            }

            val pinned = pinnedPeerCertificate(context)
            if (pinned == null) {
                pinPeerCertificate(context, cert)
                clearAuthError(context)
                return
            }
            val expectedFingerprint = fingerprintFor(pinned)
            if (incomingFingerprint != expectedFingerprint) {
                markAuthError(context)
                throw CertificateException("Pinned certificate mismatch")
            }
            clearAuthError(context)
        }
    }

    private inline fun SharedPreferences.update(block: SharedPreferences.Editor.() -> Unit) {
        edit().apply {
            block()
            apply()
        }
    }

    private fun SharedPreferences.getBase64(key: String): ByteArray? =
        getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    private fun SharedPreferences.requireBase64(key: String): ByteArray =
        getBase64(key) ?: throw IllegalStateException("identity missing")

    private fun ByteArray.toBase64String(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)
}

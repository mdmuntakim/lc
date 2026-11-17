package dev.lc.console

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * Self-signed TLS certificate for the opt-in LAN mode, generated on the device
 * with BouncyCastle and stored in an app-private PKCS#12 keystore.
 *
 * The certificate is not trusted by any CA on purpose: the client sees a
 * certificate warning, which is exactly the point — a stranger on the LAN
 * cannot silently impersonate the console, and the pairing token remains the
 * actual credential. The keystore never leaves the app sandbox.
 */
object Tls {

    private const val CERT_FILE = "lc-tls.p12"
    private const val ALIAS = "lc-console"

    init {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }

    fun factory(context: Context): SSLServerSocketFactory {
        val ksFile = File(context.filesDir, CERT_FILE)
        val pass = keystorePassword(context)
        if (!ksFile.exists()) {
            generate(ksFile, pass)
        }
        val ks = KeyStore.getInstance("PKCS12")
        ksFile.inputStream().use { ks.load(it, pass) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pass)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(kmf.keyManagers, null, null)
        return ssl.serverSocketFactory
    }

    fun reset(context: Context) {
        File(context.filesDir, CERT_FILE).delete()
    }

    private fun generate(file: File, pass: CharArray) {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val issuer = X500Name("CN=lc-console,O=LC")
        val names = GeneralNames(
            arrayOf(
                GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                GeneralName(GeneralName.dNSName, "localhost")
            )
        )
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger(63, SecureRandom()),
            Date(now),
            Date(now + 10L * 365 * 24 * 60 * 60 * 1000),
            issuer,
            kp.public
        ).addExtension(Extension.subjectAlternativeName, false, names)

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, pass)
        ks.setKeyEntry(ALIAS, kp.private, pass, arrayOf(cert))
        file.parentFile?.mkdirs()
        file.outputStream().use { ks.store(it, pass) }
    }

    /** Random per-install password, kept in app-private prefs (uninstall removes it). */
    private fun keystorePassword(context: Context): CharArray {
        val sp = context.getSharedPreferences("lc", Context.MODE_PRIVATE)
        var p = sp.getString("tlsKeystorePass", null)
        if (p == null) {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            p = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            sp.edit().putString("tlsKeystorePass", p).apply()
        }
        return p.toCharArray()
    }
}

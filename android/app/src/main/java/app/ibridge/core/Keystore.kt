package app.ibridge.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/** Android's static key `A`. The private half never leaves the Keystore. */
object Identity : StaticKey {
    private const val ALIAS = "ibridge-a-v1"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun entry(): KeyStore.PrivateKeyEntry {
        val ks = keyStore()
        (ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .build()
            )
            generateKeyPair()
        }
        return ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    override val publicKey: PublicKey get() = entry().certificate.publicKey

    override fun agree(peer: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH", "AndroidKeyStore").run {
        init(entry().privateKey)
        doPhase(peer, true)
        generateSecret()
    }
}

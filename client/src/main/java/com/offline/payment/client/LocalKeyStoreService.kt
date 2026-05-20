package com.offline.payment.client

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64


class LocalKeyStoreService {

    companion object {
        private const val KEY_PROVIDER = "AndroidKeyStore"
        private const val ALIAS_ALICE_KEY = "alice_offline_signing_key"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(KEY_PROVIDER).apply {
        load(null)
    }

    /**
     * Generates a 2048-bit RSA KeyPair directly INSIDE the phone's hardware security chip.
     * The Private key can NEVER be extracted or read by any app (including ours); it can only be used to sign.
     */
    fun generateSecureKeyPair(): KeyPair {
        if (!keyStore.containsAlias(ALIAS_ALICE_KEY)) {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                KEY_PROVIDER
            )

            val spec = KeyGenParameterSpec.Builder(
                ALIAS_ALICE_KEY,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).run {
                setKeySize(2048)
                setDigests(KeyProperties.DIGEST_SHA256)
                setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                build()
            }

            keyPairGenerator.initialize(spec)
            keyPairGenerator.generateKeyPair()
        }

        // Retrieve the references from the secure enclave
        val privateKey = keyStore.getKey(ALIAS_ALICE_KEY, null) as PrivateKey
        val publicKey = keyStore.getCertificate(ALIAS_ALICE_KEY).publicKey as PublicKey

        return KeyPair(publicKey, privateKey)
    }

    /**
     * Helper to export Alice's public key as a Base64 string so she can give it to the Bank
     * during the initial onboarding step.
     */
    fun getPublicKeyBase64(publicKey: PublicKey): String {
        return Base64.getEncoder().encodeToString(publicKey.encoded)
    }
}
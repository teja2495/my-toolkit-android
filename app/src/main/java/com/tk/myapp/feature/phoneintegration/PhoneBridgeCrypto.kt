package com.tk.myapp.feature.phoneintegration

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PhoneBridgeCrypto {
    private val random = SecureRandom()

    fun getOrCreateIdentityKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = IDENTITY_KEY_ALIAS
        if (keyStore.containsAlias(alias)) {
            val privateKey = keyStore.getKey(alias, null) as java.security.PrivateKey
            val publicKey = keyStore.getCertificate(alias).publicKey
            return KeyPair(publicKey, privateKey)
        }

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_AGREE_KEY
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        return generator.generateKeyPair()
    }

    fun publicKeyToBase64(publicKey: PublicKey): String =
        Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)

    fun publicKeyFromBase64(value: String): PublicKey {
        val encoded = Base64.decode(value, Base64.NO_WRAP)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    }

    fun deriveSessionKey(localKeyPair: KeyPair, remotePublicKey: PublicKey, salt: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localKeyPair.private)
        agreement.doPhase(remotePublicKey, true)
        val sharedSecret = agreement.generateSecret()
        return hkdfSha256(sharedSecret, salt, "tk-toolkit-phone-v1".toByteArray(), 32)
    }

    fun verificationCode(localPublicKeyBase64: String, remotePublicKeyBase64: String): String {
        val ordered = listOf(localPublicKeyBase64, remotePublicKeyBase64).sorted().joinToString(":")
        val digest = MessageDigest.getInstance("SHA-256").digest(ordered.toByteArray())
        val number = ByteBuffer.wrap(digest.copyOfRange(0, 4)).int.toLong() and 0x7fffffff
        return (number % 1_000_000).toString().padStart(6, '0')
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun encrypt(sessionKey: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(sessionKey: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 12)
        val nonce = ciphertext.copyOfRange(0, 12)
        val body = ciphertext.copyOfRange(12, ciphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(body)
    }

    private fun hkdfSha256(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(secret)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val copyLength = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, copyLength)
            offset += copyLength
            counter += 1
        }
        return output
    }

    companion object {
        private const val IDENTITY_KEY_ALIAS = "toolkit_phone_bridge_identity_v1"
    }
}

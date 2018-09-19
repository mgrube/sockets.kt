package fr.rhaz.sockets

import java.io.IOException
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object B64 {
    fun to(data: ByteArray) = Base64.getEncoder().encodeToString(data)
    fun from(data: String) = Base64.getDecoder().decode(data)
}

object RSA {

    fun generate() = KeyPairGenerator.getInstance("RSA").generateKeyPair()

    fun encrypt(data: String, key: PublicKey) = try {
        Cipher.getInstance("RSA").run {
            init(Cipher.ENCRYPT_MODE, key)
            B64.to(doFinal(data.toByteArray()))
        }
    } catch (e: Exception) {null}

    fun decrypt(data: String, key: PrivateKey) = try {
        Cipher.getInstance("RSA").run {
            init(Cipher.DECRYPT_MODE, key)
            String(doFinal(B64.from(data)))
        }
    } catch (e: Exception) {null}

    @Throws(GeneralSecurityException::class, IOException::class)
    fun loadPrivateKey(key64: String) = B64.from(key64).run {
        KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(this))
            .also{Arrays.fill(this, 0.toByte())}
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    fun loadPublicKey(key64: String) = B64.from(key64).run {
        KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(this))
    }

    @Throws(GeneralSecurityException::class)
    fun savePrivateKey(priv: PrivateKey) = KeyFactory.getInstance("RSA")
        .getKeySpec(priv, PKCS8EncodedKeySpec::class.java)
        .encoded.run{
            B64.to(this).also{Arrays.fill(this, 0.toByte())}
        }

    @Throws(GeneralSecurityException::class)
    fun savePublicKey(publ: PublicKey) = KeyFactory.getInstance("RSA")
        .getKeySpec(publ, X509EncodedKeySpec::class.java)
        .let { B64.to(it.encoded) }
}

object AES {

    fun generate() = KeyGenerator.getInstance("AES").run {
        init(128)
        generateKey()
    }

    fun encrypt(data: String, key: SecretKey) = try {
        Cipher.getInstance("AES").run {
            init(Cipher.ENCRYPT_MODE, key)
            B64.to(doFinal(data.toByteArray()))
        }
    } catch(ex: Exception) {null}

    fun decrypt(data: String, key: SecretKey) = try {
        Cipher.getInstance("AES").run {
            init(Cipher.DECRYPT_MODE, key)
            String(doFinal(B64.from(data)))
        }
    } catch(ex: Exception) {null}

    fun toKey(key: String) = B64.from(key).run {
        SecretKeySpec(this, 0, size, "AES")
    }

    fun toString(key: SecretKey) = B64.to(key.encoded)
}

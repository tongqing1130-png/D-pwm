package com.yg.minivault

import com.yg.minivault.crypto.PasswordGenerator
import com.yg.minivault.crypto.SealedBox
import com.yg.minivault.crypto.VaultCrypto
import com.yg.minivault.crypto.WrongPasswordException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultCryptoTest {

    private val password = "correct horse battery staple".toCharArray()

    @Test
    fun deriveKek_isDeterministicForSameSalt() {
        val salt = VaultCrypto.newSalt()
        val a = VaultCrypto.deriveKek(password, salt).encoded
        val b = VaultCrypto.deriveKek(password, salt).encoded
        assertArrayEquals(a, b)
        assertEquals(32, a.size)
    }

    @Test
    fun deriveKek_changesWithDifferentSalt() {
        val a = VaultCrypto.deriveKek(password, VaultCrypto.newSalt()).encoded
        val b = VaultCrypto.deriveKek(password, VaultCrypto.newSalt()).encoded
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun wrapUnwrap_roundTrip() {
        val salt = VaultCrypto.newSalt()
        val kek = VaultCrypto.deriveKek(password, salt)
        val dek = VaultCrypto.newDek()
        val box = VaultCrypto.seal(kek, dek.encoded)
        val recovered = VaultCrypto.open(kek, box)
        assertArrayEquals(dek.encoded, recovered)
    }

    @Test(expected = WrongPasswordException::class)
    fun unwrap_withWrongPassword_throws() {
        val salt = VaultCrypto.newSalt()
        val kek = VaultCrypto.deriveKek(password, salt)
        val wrong = VaultCrypto.deriveKek("wrong password".toCharArray(), salt)
        val dek = VaultCrypto.newDek()
        val box = VaultCrypto.seal(kek, dek.encoded)
        VaultCrypto.open(wrong, box)
    }

    @Test
    fun itemCipher_usesRandomNonce() {
        val dek = VaultCrypto.newDek()
        val plain = "hello vault".toByteArray()
        val aad = "id-1|v1".toByteArray()
        val box1 = VaultCrypto.seal(dek, plain, aad)
        val box2 = VaultCrypto.seal(dek, plain, aad)
        assertNotEquals(box1.nonceB64, box2.nonceB64)
        assertNotEquals(box1.ctB64, box2.ctB64)
        assertArrayEquals(plain, VaultCrypto.open(dek, box1, aad))
    }

    @Test(expected = WrongPasswordException::class)
    fun itemCipher_rejectsFlippedCiphertextBit() {
        val dek = VaultCrypto.newDek()
        val plain = "secret".toByteArray()
        val aad = "id-1|v1".toByteArray()
        val box = VaultCrypto.seal(dek, plain, aad)
        val ct = VaultCrypto.unb64(box.ctB64)
        ct[0] = (ct[0].toInt() xor 0x01).toByte()
        VaultCrypto.open(dek, SealedBox(box.nonceB64, VaultCrypto.b64(ct)), aad)
    }

    @Test(expected = WrongPasswordException::class)
    fun itemCipher_rejectsWrongAad() {
        val dek = VaultCrypto.newDek()
        val box = VaultCrypto.seal(dek, "secret".toByteArray(), "id-1|v1".toByteArray())
        VaultCrypto.open(dek, box, "id-2|v1".toByteArray())
    }

    @Test
    fun passwordGenerator_respectsLengthAndClasses() {
        val pw = PasswordGenerator.generate(length = 24)
        assertEquals(24, pw.length)
        assertTrue(pw.any { it.isLowerCase() })
        assertTrue(pw.any { it.isUpperCase() })
        assertTrue(pw.any { it.isDigit() })
        assertTrue(pw.any { !it.isLetterOrDigit() })
    }

    @Test
    fun passwordGenerator_isNotRepeating() {
        val a = PasswordGenerator.generate(length = 20)
        val b = PasswordGenerator.generate(length = 20)
        assertNotEquals(a, b)
    }

    @Test
    fun passwordScore_ranksLongComplexHigher() {
        assertTrue(PasswordGenerator.score("abc") < PasswordGenerator.score("Abcdefgh1234!@#$"))
    }
}
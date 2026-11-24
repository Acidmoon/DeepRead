package com.vibecoding.reader.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 可替换的加解密，便于单测。 */
interface PayloadCipher {
    fun encrypt(plain: String): String
    fun decrypt(payload: String): String?
}

/**
 * 将 PDF 密码存放在 [Context.getNoBackupFilesDir]，系统备份不会带走。
 * 密文使用 Android Keystore 中的 AES 密钥；密钥本身也不会随备份迁移。
 */
class PdfPasswordStore(
    private val file: File,
    private val cipher: PayloadCipher
) {
    fun get(bookId: String): String? {
        val payload = readAll()[bookId] ?: return null
        return cipher.decrypt(payload)
    }

    fun put(bookId: String, password: String) {
        if (bookId.isBlank()) return
        val all = readAll().toMutableMap()
        all[bookId] = cipher.encrypt(password)
        writeAll(all)
    }

    fun remove(bookId: String) {
        val all = readAll().toMutableMap()
        if (all.remove(bookId) != null) writeAll(all)
    }

    private fun readAll(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                line.substring(0, idx) to line.substring(idx + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeAll(all: Map<String, String>) {
        file.parentFile?.mkdirs()
        if (all.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }
        file.writeText(all.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
    }

    companion object {
        private const val FILE_NAME = "pdf-passwords.dat"

        @Volatile
        private var instance: PdfPasswordStore? = null

        fun get(context: Context): PdfPasswordStore {
            return instance ?: synchronized(this) {
                instance ?: PdfPasswordStore(
                    file = File(context.applicationContext.noBackupFilesDir, FILE_NAME),
                    cipher = AndroidKeystoreCipher()
                ).also { instance = it }
            }
        }
    }
}

class AndroidKeystoreCipher(
    private val alias: String = "deepread_pdf_password_aes"
) : PayloadCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(1 + iv.size + encrypted.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(encrypted, 0, packed, 1 + iv.size, encrypted.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    override fun decrypt(payload: String): String? {
        return runCatching {
            val packed = Base64.decode(payload, Base64.NO_WRAP)
            if (packed.isEmpty()) return null
            val ivSize = packed[0].toInt() and 0xFF
            if (ivSize <= 0 || packed.size <= 1 + ivSize) return null
            val iv = packed.copyOfRange(1, 1 + ivSize)
            val encrypted = packed.copyOfRange(1 + ivSize, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

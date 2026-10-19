package com.meetdheeran.notch.face

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The face saved at setup: its prints (numbers, not photos) and its pass mark, plus the looks it has learned since
 * ([FaceLearner]) — encrypted with a key kept in the phone's secure hardware (Android Keystore). Only Notch can read
 * them; nothing is uploaded or backed up.
 */
object FaceStore {
    private const val ALIAS = "notch_face"
    private const val IV = 12
    private const val MAGIC = 0x4D463246 // "MF2F": FaceNet-512 setup prints
    private const val MAGIC_LEARNED = 0x4D463247 // "MF2G": the same, plus learned prints

    /** What setup saved, and what was learned since. Unlocks compare against [all]. */
    class Saved(val prints: List<FloatArray>, val threshold: Float, val learned: List<FloatArray> = emptyList()) {
        val all: List<FloatArray> get() = prints + learned
    }

    private fun file(ctx: Context) = File(ctx.noBackupFilesDir, "face2.bin")

    /** The first face unlock's prints: from another model, of no use to this one. */
    private fun oldFile(ctx: Context) = File(ctx.noBackupFilesDir, "face.bin")

    fun enrolled(ctx: Context): Boolean = file(ctx).exists()

    /** Set up with the first face unlock: face unlock was upgraded since, so the face has to be set up again. */
    fun needsRedo(ctx: Context): Boolean = !enrolled(ctx) && oldFile(ctx).exists()

    fun save(ctx: Context, prints: List<FloatArray>, threshold: Float, learned: List<FloatArray> = emptyList()) {
        require(prints.isNotEmpty())
        val dim = prints.first().size
        val plain = ByteBuffer.allocate(20 + (prints.size + learned.size) * dim * 4).apply {
            putInt(MAGIC_LEARNED)
            putInt(prints.size)
            putInt(learned.size)
            putInt(dim)
            putFloat(threshold)
            for (p in prints + learned) for (x in p) putFloat(x)
        }.array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain)
        file(ctx).writeBytes(cipher.iv + sealed)
        oldFile(ctx).delete()
    }

    fun load(ctx: Context): Saved? = runCatching {
        val all = file(ctx).readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV))
        }
        val buf = ByteBuffer.wrap(cipher.doFinal(all, IV, all.size - IV))
        val magic = buf.int
        check(magic == MAGIC || magic == MAGIC_LEARNED)
        val n = buf.int
        val learned = if (magic == MAGIC_LEARNED) buf.int else 0
        val dim = buf.int
        val threshold = buf.float
        Saved(List(n) { FloatArray(dim) { buf.float } }, threshold, List(learned) { FloatArray(dim) { buf.float } })
    }.getOrNull()?.takeIf { it.prints.isNotEmpty() }

    /** Adds a learned look ([FaceLearner.add] keeps the newest few); the setup prints and pass mark stay as they are. */
    @Synchronized
    fun learn(ctx: Context, print: FloatArray) {
        val saved = load(ctx) ?: return
        save(ctx, saved.prints, saved.threshold, FaceLearner.add(saved.learned, print))
    }

    /** Forgets every learned look, keeping the face as it was set up. */
    @Synchronized
    fun forgetLearned(ctx: Context) {
        val saved = load(ctx) ?: return
        save(ctx, saved.prints, saved.threshold)
    }

    fun remove(ctx: Context) {
        file(ctx).delete()
        oldFile(ctx).delete()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}

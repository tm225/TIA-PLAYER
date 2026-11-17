package com.tia.player
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.URLConnection
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class VItem(val file: String, val name: String, val size: Long)

/** Coffre chiffré : AES-256-CTR, clé dérivée du mot de passe (PBKDF2, 120 000 itérations).
 *  Fichiers vidéo ET index (noms) chiffrés dans filesDir/vault, invisibles pour les autres applications. */
object Vault {
    @Volatile var key: SecretKey? = null
    fun derive(pr: Prefs, pw: String): SecretKey {
        val salt = Base64.decode(pr.p.getString("salt", ""), 0) + byteArrayOf(1)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, 120000, 256)).encoded
        return SecretKeySpec(raw, "AES")
    }
    private fun dir(c: Context) = File(c.filesDir, "vault").apply { mkdirs() }
    private fun cipher(mode: Int, iv: ByteArray) = Cipher.getInstance("AES/CTR/NoPadding").apply { init(mode, key, IvParameterSpec(iv)) }
    private fun enc(ins: InputStream, out: File) {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        FileOutputStream(out).use { o -> o.write(iv); CipherOutputStream(o, cipher(Cipher.ENCRYPT_MODE, iv)).use { ins.copyTo(it, 1 shl 16) } }
    }
    private fun dec(f: File, out: OutputStream) {
        FileInputStream(f).use { i -> val iv = ByteArray(16); i.read(iv); CipherInputStream(i, cipher(Cipher.DECRYPT_MODE, iv)).copyTo(out, 1 shl 16) }
    }
    private fun idx(c: Context) = File(dir(c), "index.tia")
    fun list(c: Context): List<VItem> {
        if (!idx(c).exists()) return emptyList()
        val b = ByteArrayOutputStream(); dec(idx(c), b); val a = JSONArray(String(b.toByteArray()))
        return List(a.length()) { val o = a.getJSONObject(it); VItem(o.getString("f"), o.getString("n"), o.getLong("s")) }
    }
    private fun save(c: Context, l: List<VItem>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("f", it.file).put("n", it.name).put("s", it.size)) }
        val tmp = File(dir(c), "index.new"); enc(ByteArrayInputStream(a.toString().toByteArray()), tmp); tmp.renameTo(idx(c))
    }
    /** Chiffre la vidéo ; l'appelant ne supprime l'original que si true. */
    fun add(c: Context, v: Vid): Boolean = runCatching {
        val id = java.util.UUID.randomUUID().toString() + ".tia"
        c.contentResolver.openInputStream(v.uri)!!.use { enc(it, File(dir(c), id)) }
        save(c, list(c) + VItem(id, v.name, v.size)); true
    }.getOrDefault(false)
    /** Déchiffre vers le cache (supprimé à la fermeture du lecteur) pour la lecture. */
    fun open(c: Context, x: VItem): File {
        val f = File(File(c.cacheDir, "tia_play").apply { mkdirs() }, x.name)
        FileOutputStream(f).use { dec(File(dir(c), x.file), it) }; return f
    }
    fun restore(c: Context, x: VItem): Boolean = runCatching {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, x.name)
            put(MediaStore.Video.Media.MIME_TYPE, URLConnection.guessContentTypeFromName(x.name) ?: "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TIA PLAYER") }
        val u = c.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
        c.contentResolver.openOutputStream(u)!!.use { dec(File(dir(c), x.file), it) }
        File(dir(c), x.file).delete(); save(c, list(c).filter { it.file != x.file }); true
    }.getOrDefault(false)
    fun lock(c: Context) { key = null; File(c.cacheDir, "tia_play").deleteRecursively() }
}

package com.margelo.nitro.queueplayer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Exported `ContentProvider` that serves locally-cached artwork files
 * to cross-process media controllers (Android Auto's gearhead, Wear,
 * Assistant). Solves the missing-cover symptom on the AA Now Playing
 * surface — gearhead resolves `artworkUri` via its own `ContentResolver`
 * in a different process and cannot read another app's private
 * `file://` storage; Media3 does not currently call
 * `grantUriPermission` for connected controllers
 * (androidx/media#2331). An exported provider serving the same files
 * via `content://` lets gearhead open them with no URI-permission
 * dance.
 *
 * Security: registers only file paths we have explicitly handed out
 * via [mapFileUri]. The URI carries an opaque token (SHA-256 prefix
 * of the file path); a malicious caller cannot probe arbitrary paths
 * without already knowing the token. Process-lifecycle: the registry
 * is in-memory, so a process death + restart drops the map; the
 * media controller re-queries our session afterwards which re-runs
 * [mapFileUri] and re-populates the registry transparently.
 *
 * Authority is `${applicationId}.rnqp.artwork` — per-consumer-app
 * unique so multiple RNQP-using apps on the same device don't clash.
 */
class ArtworkContentProvider : ContentProvider() {

  override fun onCreate(): Boolean {
    appContextRef = context?.applicationContext
    return appContextRef != null
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?
  ): Cursor? = null

  override fun getType(uri: Uri): String = "image/*"

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?
  ): Int = 0

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
    val token = uri.pathSegments.firstOrNull() ?: return null
    val file = registry[token] ?: return null
    return try {
      ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (_: Throwable) {
      null
    }
  }

  companion object {
    private const val AUTHORITY_SUFFIX = ".rnqp.artwork"
    private val registry = ConcurrentHashMap<String, File>()

    @Volatile
    private var appContextRef: Context? = null

    /** Authority owned by THIS consumer app, e.g.
     *  `com.consumer.app.rnqp.artwork`. Returns null only before the
     *  provider's onCreate runs (which happens at app start). */
    fun authority(): String? = appContextRef?.let { it.packageName + AUTHORITY_SUFFIX }

    /**
     * Convert a `file://` URI for a locally-cached artwork file into
     * a `content://` URI served by this provider. Returns the input
     * unchanged when it is not `file://`, or when the file is
     * unreadable. The returned URI is parcel-safe to hand off to
     * cross-process controllers (Android Auto, Wear, Assistant).
     */
    fun mapFileUri(fileUri: Uri): Uri {
      if (fileUri.scheme != "file") return fileUri
      val path = fileUri.path ?: return fileUri
      val file = File(path)
      if (!file.exists() || !file.canRead()) return fileUri
      val auth = authority() ?: return fileUri
      val token = tokenFor(path)
      registry[token] = file
      return Uri.Builder()
        .scheme("content")
        .authority(auth)
        .appendPath(token)
        .build()
    }

    /** SHA-256 prefix of [path], 32 hex chars. Stable per path so
     *  re-registering the same file is idempotent — the registry map
     *  ends up with a single entry per cached file regardless of how
     *  many MediaItems reference it. */
    private fun tokenFor(path: String): String {
      val digest = MessageDigest.getInstance("SHA-256")
      val bytes = digest.digest(path.toByteArray(Charsets.UTF_8))
      val hex = StringBuilder(bytes.size * 2)
      for (b in bytes) {
        val v = b.toInt() and 0xFF
        hex.append("0123456789abcdef"[v ushr 4])
        hex.append("0123456789abcdef"[v and 0x0F])
      }
      return hex.substring(0, 32)
    }
  }
}

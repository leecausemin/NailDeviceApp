package com.example.naildeviceapp.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.example.naildeviceapp.model.SaveLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The chosen destination, plus the folder behind it once one has been granted. */
data class RecordingLocation(
    val mode: SaveLocation = SaveLocation.Default,
    val treeUri: Uri? = null,
    /** Human-readable path of [treeUri], e.g. `Download/NailData`. Empty under [SaveLocation.Default]. */
    val folderLabel: String = ""
) {
    /** Custom only counts once a folder has actually been picked and the grant still holds. */
    val usesCustomFolder: Boolean get() = mode == SaveLocation.Custom && treeUri != null
}

/**
 * Remembers where recordings go, across restarts — a folder picked once should not have to be
 * picked again every session.
 *
 * Storing the URI is not enough on its own. A tree URI handed back by the picker is only writable
 * for the life of the process unless the grant is explicitly persisted, and a grant can also be
 * revoked later (the user deletes the folder, or ejects the SD card). Both cases are checked on
 * load, because the alternative is discovering it at the end of a recording, when the samples are
 * already captured and there is nowhere to put them.
 */
class RecordingLocationPreferences(context: Context) {

    private val appContext = context.applicationContext
    private val store = appContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    private val _location = MutableStateFlow(load())
    val location: StateFlow<RecordingLocation> = _location.asStateFlow()

    fun useDefault() {
        store.edit().putString(KEY_MODE, SaveLocation.Default.name).apply()
        _location.value = _location.value.copy(mode = SaveLocation.Default)
    }

    /**
     * Records the folder returned by the system picker. Takes the persistable grant first: if that
     * fails there is no point storing the URI, because writing to it later would fail too.
     */
    fun useFolder(treeUri: Uri) {
        val taken = runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.isSuccess

        if (!taken) {
            Log.e(TAG, "could not persist write access to $treeUri; staying on the default folder")
            useDefault()
            return
        }

        val label = labelFor(treeUri)
        store.edit()
            .putString(KEY_MODE, SaveLocation.Custom.name)
            .putString(KEY_TREE_URI, treeUri.toString())
            .putString(KEY_LABEL, label)
            .apply()

        _location.value = RecordingLocation(SaveLocation.Custom, treeUri, label)
    }

    private fun load(): RecordingLocation {
        val stored = store.getString(KEY_TREE_URI, null)?.toUri() ?: return RecordingLocation()

        if (!stillWritable(stored)) {
            Log.w(TAG, "lost write access to $stored; falling back to the default folder")
            store.edit().remove(KEY_TREE_URI).remove(KEY_LABEL).putString(
                KEY_MODE, SaveLocation.Default.name
            ).apply()
            return RecordingLocation()
        }

        val mode = runCatching {
            SaveLocation.valueOf(store.getString(KEY_MODE, null) ?: SaveLocation.Default.name)
        }.getOrDefault(SaveLocation.Default)

        return RecordingLocation(mode, stored, store.getString(KEY_LABEL, "").orEmpty())
    }

    private fun stillWritable(treeUri: Uri): Boolean =
        appContext.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }

    /** `primary:Download/NailData` reads better as `Download/NailData`. */
    private fun labelFor(treeUri: Uri): String = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        documentId.substringAfter(':', documentId).ifEmpty { documentId }
    }.getOrDefault(treeUri.lastPathSegment.orEmpty())

    private fun String.toUri(): Uri? = runCatching { Uri.parse(this) }.getOrNull()

    private companion object {
        const val TAG = "RecordingLocation"
        const val STORE_NAME = "recording_location"
        const val KEY_MODE = "mode"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_LABEL = "label"
    }
}

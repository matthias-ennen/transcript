package de.matthiasennen.transcript.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

data class RecordingFolder(
    val treeUri: Uri,
    val displayName: String
)

class RecordingFolderPreferences(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadValid(): RecordingFolder? {
        val treeUri = preferences.getString(TREE_URI_KEY, null)?.let(Uri::parse) ?: return null
        val hasWritePermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }
        if (!hasWritePermission) {
            clear()
            return null
        }
        return RecordingFolder(treeUri, queryDisplayName(treeUri) ?: "Ausgewählter Ordner")
    }

    fun save(treeUri: Uri, grantedFlags: Int): RecordingFolder? {
        val flags = grantedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
            .getOrElse { return null }
        val folder = RecordingFolder(treeUri, queryDisplayName(treeUri) ?: "Ausgewählter Ordner")
        preferences.edit()
            .putString(TREE_URI_KEY, treeUri.toString())
            .putString(DISPLAY_NAME_KEY, folder.displayName)
            .apply()
        return folder
    }

    fun clear() {
        preferences.edit().remove(TREE_URI_KEY).remove(DISPLAY_NAME_KEY).apply()
    }

    private fun queryDisplayName(treeUri: Uri): String? = runCatching {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)
        }
    }.getOrNull()

    private companion object {
        const val PREFERENCES_NAME = "recording_folder"
        const val TREE_URI_KEY = "tree_uri"
        const val DISPLAY_NAME_KEY = "display_name"
    }
}

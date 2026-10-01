package com.luno.mobile.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Persists the user's chosen music folder (SAF tree URI) so the folder
 * destination survives app restarts and can be re-imported / changed from
 * the Settings drawer.
 */
class MusicFolderRepository(
    context: Context,
    private val permissionPersister: TreePermissionPersister = TreePermissionPersister.Default(context)
) {

    private val context = context.applicationContext
    private val prefs = context
        .getSharedPreferences("music_folder", Context.MODE_PRIVATE)

    /**
     * Stores the tree selected by OpenDocumentTree and persists both grants.
     * Library import only needs READ, but download copies require WRITE too.
     * Returns whether a durable read+write grant is now available.
     */
    fun saveTreeUri(uri: Uri): Boolean {
        listOf(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ).forEach { flag ->
            try {
                permissionPersister.persist(uri, flag)
            } catch (error: SecurityException) {
                // Keep folder import available even if the provider refuses
                // one grant; downloads will be kept in app storage with a
                // clear action for the user.
                Log.w(TAG, "Could not persist music-folder grant $flag (${error.javaClass.simpleName})")
            } catch (error: Exception) {
                Log.w(TAG, "Could not persist music-folder grant $flag (${error.javaClass.simpleName})")
            }
        }
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        return hasPersistedReadWritePermission(uri)
    }

    fun loadTreeUri(): String? = prefs.getString(KEY_TREE_URI, null)

    /** True only when Android reports durable access for reading and writing the selected tree. */
    fun hasPersistedReadWritePermission(uri: Uri? = loadTreeUri()?.let(Uri::parse)): Boolean {
        if (uri == null) return false
        val grants = context.contentResolver.persistedUriPermissions
            .filter { it.uri == uri }
        return grants.any { it.isReadPermission } && grants.any { it.isWritePermission }
    }

    /** Copies a local file into the selected SAF folder and returns its URI. */
    fun copyFileToSelectedFolder(
        sourceFile: File,
        displayName: String,
        mimeType: String,
        reuseExisting: Boolean = true,
        overwriteExisting: Boolean = false
    ): Uri? {
        val savedTreeUri = loadTreeUri()?.takeUnless { it.isBlank() } ?: return null
        val treeUri = Uri.parse(savedTreeUri)
        if (!hasPersistedReadWritePermission(treeUri)) {
            throw IOException(
                "Music folder write access is missing. Choose the Music folder again in Settings."
            )
        }
        val rootDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            throw IOException("The selected music folder is no longer available", e)
        }
        val parentUri = try {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                rootDocumentId
            )
        } catch (e: Exception) {
            throw IOException("The selected music folder is no longer available", e)
        }

        // Imports reuse matching documents; generated retries replace only
        // their app-owned destination, while preview promotion rejects collisions.
        findChildByName(treeUri, rootDocumentId, displayName)?.let { existing ->
            if (overwriteExisting) {
                try {
                    if (!DocumentsContract.deleteDocument(context.contentResolver, existing)) {
                        throw IOException("Could not replace the existing download")
                    }
                } catch (error: Exception) {
                    if (error is IOException) throw error
                    throw IOException("Could not replace the existing download", error)
                }
            } else {
                if (reuseExisting) return existing
                throw IOException("A file named $displayName already exists in the selected folder")
            }
        }

        var destinationUri: Uri? = null
        try {
            destinationUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                mimeType,
                displayName
            ) ?: throw IOException("Could not create a file in the selected music folder")

            context.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not write to the selected music folder")

            return destinationUri
        } catch (e: Exception) {
            destinationUri?.let { created ->
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, created) }
            }
            if (e is IOException) throw e
            throw IOException("Could not save the song to the selected music folder", e)
        }
    }

    private fun findChildByName(
        treeUri: Uri,
        parentDocumentId: String,
        displayName: String
    ): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId
        )
        return try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    if (idIndex >= 0 && nameIndex >= 0 &&
                        cursor.getString(nameIndex) == displayName &&
                        (mimeIndex < 0 || cursor.getString(mimeIndex) != DocumentsContract.Document.MIME_TYPE_DIR)
                    ) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            cursor.getString(idIndex)
                        )
                    }
                }
                null
            }
        } catch (e: Exception) {
            throw IOException("Could not inspect the selected music folder", e)
        }
    }

    fun destinationFailureNotice(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.toList()
        return when {
            causes.any { it is SecurityException } ||
                causes.any { it.message?.contains("write access is missing", ignoreCase = true) == true } ->
                "Saved in Luno app storage. Choose the Music folder again in Settings to restore write access."
            causes.any { it is FileNotFoundException } ->
                "Saved in Luno app storage. The Music folder is unavailable; reconnect the storage and choose it again in Settings."
            else ->
                "Saved in Luno app storage. The Music folder could not accept this file; check storage space and choose the folder again in Settings."
        }
    }

    fun interface TreePermissionPersister {
        fun persist(uri: Uri, flags: Int)

        companion object {
            fun Default(context: Context): TreePermissionPersister =
                TreePermissionPersister { uri, flags ->
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                }
        }
    }

    companion object {
        private const val TAG = "MusicFolderRepository"
        private const val KEY_TREE_URI = "tree_uri"
    }
}

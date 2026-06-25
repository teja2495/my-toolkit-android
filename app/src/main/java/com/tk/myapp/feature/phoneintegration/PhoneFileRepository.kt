package com.tk.myapp.feature.phoneintegration

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.database.Cursor
import android.net.Uri
import android.util.Log
import android.provider.MediaStore
import androidx.core.content.ContextCompat

class PhoneFileRepository(private val context: Context) {
    fun hasRequiredPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun listFiles(category: PhoneFileCategory, pageSize: Int, pageToken: Int): List<PhoneFileMetadata> {
        val offset = maxOf(0, pageToken)
        val limit = pageSize.coerceIn(1, 100)
        val collection = when (category) {
            PhoneFileCategory.PhotosVideos -> MediaStore.Files.getContentUri("external")
            PhoneFileCategory.Documents -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            PhoneFileCategory.Music -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            PhoneFileCategory.Other -> MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val selection = when (category) {
            PhoneFileCategory.PhotosVideos ->
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            PhoneFileCategory.Documents ->
                "${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL"
            PhoneFileCategory.Music ->
                null
            PhoneFileCategory.Other ->
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        }
        val selectionArgs = when (category) {
            PhoneFileCategory.PhotosVideos -> arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            )
            PhoneFileCategory.Documents -> null
            PhoneFileCategory.Music -> null
            PhoneFileCategory.Other -> arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE.toString())
        }
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT $limit OFFSET $offset"

        val files = runCatching {
            context.contentResolver.queryFiles(
                collection = collection,
                projection = projection,
                selection = selection,
                selectionArgs = selectionArgs,
                sortOrder = sortOrder,
                limit = limit,
                offset = offset
            )?.use { cursor -> cursor.toMetadata(collection) } ?: emptyList()
        }.onFailure { error ->
            Log.e(
                TAG,
                "listFiles failed category=${category.protocolValue} collection=$collection",
                error
            )
        }.getOrDefault(emptyList())
        Log.d(
            TAG,
            "listFiles category=${category.protocolValue} collection=$collection pageSize=$limit pageToken=$offset selection=$selection resultCount=${files.size}"
        )
        if (files.isNotEmpty()) {
            val sample = files.take(3).joinToString { it.displayName }
            Log.d(TAG, "listFiles sample=${sample}")
        }
        return files
    }

    private fun ContentResolver.queryFiles(
        collection: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String,
        limit: Int,
        offset: Int
    ): Cursor? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val queryArgs = Bundle().apply {
                selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
                selectionArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.MediaColumns.DATE_MODIFIED)
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
            query(collection, projection, queryArgs, null)
        } else {
            query(collection, projection, selection, selectionArgs, sortOrder)
        }
    }

    private fun Cursor.toMetadata(collection: Uri): List<PhoneFileMetadata> {
        val idColumn = getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameColumn = getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val sizeColumn = getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val modifiedColumn = getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val mimeColumn = getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val files = mutableListOf<PhoneFileMetadata>()
        while (moveToNext()) {
            val id = getLong(idColumn)
            val uri = ContentUris.withAppendedId(collection, id)
            files += PhoneFileMetadata(
                id = id.toString(),
                displayName = getString(nameColumn).orEmpty(),
                documentUri = uri.toString(),
                sizeBytes = getLong(sizeColumn),
                modifiedAtMillis = getLong(modifiedColumn) * 1000,
                mimeType = getString(mimeColumn).orEmpty(),
                thumbnailBase64 = null
            )
        }
        return files
    }

    companion object {
        private const val TAG = "PhoneFileRepository"
    }
}

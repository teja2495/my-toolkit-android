package com.tk.myapp.feature.phoneintegration

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

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
            Log.d(TAG, "listFiles sample=$sample")
        }
        return files
    }

    fun openFileStream(documentUri: String): InputStream? {
        val uri = runCatching { Uri.parse(documentUri) }.getOrNull() ?: return null
        return context.contentResolver.openInputStream(uri)
    }

    fun prepareSharedFile(uri: Uri): SharedPhoneFile? {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val metadata = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                Pair(
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null,
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                )
            }
        val filename = sanitizeFilename(metadata?.first ?: uri.lastPathSegment ?: "Shared File")
        return SharedPhoneFile(
            uri = uri,
            filename = filename,
            mimeType = mimeType,
            sizeBytes = metadata?.second
        )
    }

    fun openSharedFileInputStream(uri: Uri): InputStream? {
        return context.contentResolver.openInputStream(uri)
    }

    fun createIncomingShareTempFile(requestId: String): File {
        val directory = File(context.cacheDir, "incoming-phone-shares")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return File(directory, requestId)
    }

    fun saveIncomingSharedFile(filename: String, mimeType: String, sourceFile: File): Uri {
        val resolver = context.contentResolver
        val safeFilename = sanitizeFilename(filename)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeFilename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val destinationUri = resolver.insert(collection, values)
            ?: error("Unable to create the Downloads file.")

        runCatching {
            resolver.openOutputStream(destinationUri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("Unable to open the Downloads destination.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(destinationUri, values, null, null)
        }.onFailure { error ->
            runCatching { resolver.delete(destinationUri, null, null) }
            throw error
        }

        return destinationUri
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
            val mimeType = getString(mimeColumn).orEmpty()
            files += PhoneFileMetadata(
                id = id.toString(),
                displayName = getString(nameColumn).orEmpty(),
                documentUri = uri.toString(),
                sizeBytes = getLong(sizeColumn),
                modifiedAtMillis = getLong(modifiedColumn) * 1000,
                mimeType = mimeType,
                thumbnailBase64 = makeThumbnailBase64(uri, mimeType)
            )
        }
        return files
    }

    private fun makeThumbnailBase64(uri: Uri, mimeType: String): String? {
        if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) {
            return null
        }

        val bitmap = runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    context.contentResolver.loadThumbnail(uri, THUMBNAIL_SIZE, null)
                mimeType.startsWith("image/") -> decodeImageThumbnail(uri)
                mimeType.startsWith("video/") -> decodeVideoThumbnail(uri)
                else -> null
            }
        }.onFailure { error ->
            Log.w(TAG, "Thumbnail generation failed for uri=$uri mimeType=$mimeType", error)
        }.getOrNull() ?: return null

        return bitmap.toBase64Jpeg()
    }

    private fun decodeImageThumbnail(uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(input, null, bounds)
            val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
            if (maxDimension <= 0) {
                return null
            }
            val sampleSize = maxOf(1, Integer.highestOneBit(maxDimension / LEGACY_THUMBNAIL_PX))
            context.contentResolver.openInputStream(uri)?.use { secondInput ->
                BitmapFactory.decodeStream(
                    secondInput,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                )
            }
        }
    }

    private fun decodeVideoThumbnail(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.frameAtTime?.scaledThumbnail()
        } catch (error: Throwable) {
            Log.w(TAG, "Video thumbnail extraction failed for uri=$uri", error)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun Bitmap.scaledThumbnail(): Bitmap {
        val width = width.coerceAtLeast(1)
        val height = height.coerceAtLeast(1)
        val largestSide = maxOf(width, height)
        if (largestSide <= TARGET_THUMBNAIL_PX) {
            return this
        }
        val scale = TARGET_THUMBNAIL_PX.toFloat() / largestSide.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun Bitmap.toBase64Jpeg(): String? {
        val scaled = scaledThumbnail()
        return ByteArrayOutputStream().use { output ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                return null
            }
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun sanitizeFilename(filename: String): String {
        val trimmed = filename.trim()
        val fallback = if (trimmed.isEmpty()) "Shared File" else trimmed
        return fallback.replace(Regex("[\\\\/:]"), "-")
    }

    companion object {
        private const val TAG = "PhoneFileRepository"
        private val THUMBNAIL_SIZE = Size(TARGET_THUMBNAIL_PX, TARGET_THUMBNAIL_PX)
        private const val TARGET_THUMBNAIL_PX = 300
        private const val LEGACY_THUMBNAIL_PX = 300
        private const val JPEG_QUALITY = 76
    }
}

data class SharedPhoneFile(
    val uri: Uri,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long?
)

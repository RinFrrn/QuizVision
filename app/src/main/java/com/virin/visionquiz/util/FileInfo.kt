package com.virin.visionquiz.util

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi

@SuppressLint("Range")
fun getFileInfo(context: Context, fileId: Long): FileDetails? {
    val projection = arrayOf(
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.DATE_ADDED,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.IS_DOWNLOAD,
        MediaStore.Files.FileColumns.RELATIVE_PATH,
        MediaStore.Files.FileColumns.MEDIA_TYPE
    )
    val selection = "${MediaStore.Files.FileColumns._ID} = ?"
    val selectionArgs = arrayOf(fileId.toString())
    val queryUri: Uri = MediaStore.Files.getContentUri("external")

    val cursor = context.contentResolver.query(
        queryUri,
        projection,
        selection,
        selectionArgs,
        null
    )

    cursor?.use {
        if (it.moveToFirst()) {
            val fileName = it.getString(it.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME))
            val fileSize = it.getLong(it.getColumnIndex(MediaStore.Files.FileColumns.SIZE))
            val mimeType = it.getString(it.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE))
            val dateAdded = it.getLong(it.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED))
            val dateModified = it.getLong(it.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED))
            val isDownload = it.getInt(it.getColumnIndex(MediaStore.Files.FileColumns.IS_DOWNLOAD))
            val relativePath = it.getString(it.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH))
            val mediaType = it.getInt(it.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE))

            val contentUri = ContentUris.withAppendedId(queryUri, fileId)

            return FileDetails(contentUri, fileName, fileSize, mimeType, dateAdded, dateModified, isDownload == 1, relativePath, mediaType)
        }
    }
    return null
}

data class FileDetails(
    val contentUri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val dateAdded: Long,
    val dateModified: Long,
    val isDownload: Boolean,
    val relativePath: String?,
    val mediaType: Int
)

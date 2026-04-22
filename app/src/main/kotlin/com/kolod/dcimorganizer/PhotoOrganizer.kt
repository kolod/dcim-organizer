package com.kolod.dcimorganizer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class PhotoOrganizer(private val context: Context) {

    /**
     * Outcome of an organize pass.
     *
     * @property movedCount number of files successfully relocated via MediaStore.
     * @property pendingPermissionUris files the app isn't allowed to modify. The caller should
     *   hand these to [MediaStore.createWriteRequest] to get the user to grant write access in
     *   a single system dialog, then call [organize] again.
     */
    data class OrganizeResult(
        val movedCount: Int,
        val pendingPermissionUris: List<Uri>
    )

    fun organize(): OrganizeResult {
        var count = 0
        val pendingUris = mutableListOf<Uri>()

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("DCIM/%")

        val queryUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val cursor = context.contentResolver.query(
            queryUri, projection, selection, selectionArgs,
            "${MediaStore.Files.FileColumns.DATE_TAKEN} ASC"
        ) ?: return OrganizeResult(0, emptyList())

        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val pathCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val dateTakenCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            val dateModCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val relativePath = it.getString(pathCol) ?: continue
                val dateTakenMs = if (!it.isNull(dateTakenCol)) it.getLong(dateTakenCol) else 0L
                val dateModMs = it.getLong(dateModCol) * 1000L
                val mimeType = it.getString(mimeCol)

                if (!isMediaMime(mimeType)) continue

                val targetRelativePath = computeTargetRelativePath(dateTakenMs, dateModMs)

                if (relativePath == targetRelativePath) continue

                val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id)
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, targetRelativePath)
                }
                try {
                    val updated = context.contentResolver.update(uri, values, null, null)
                    if (updated > 0) count++
                } catch (e: SecurityException) {
                    // App lacks write access to this file (owned by another app).
                    // Collect it so the caller can request permission in one batch dialog.
                    pendingUris.add(uri)
                } catch (e: Exception) {
                    // Some files may simply not be moveable — skip them.
                }
            }
        }
        return OrganizeResult(count, pendingUris)
    }

    companion object {
        /**
         * Picks the best available timestamp and turns it into the `DCIM/<year>/<MM>/` target
         * that [organize] moves files to. `dateTakenMs` wins if it's positive; otherwise
         * `dateModifiedMs` is used. The month is zero-padded.
         *
         * Exposed for unit testing and intentionally pure — no Android framework dependencies
         * beyond [Calendar].
         *
         * @param dateTakenMs `MediaStore.Files.FileColumns.DATE_TAKEN` in milliseconds (0 if
         *   unknown).
         * @param dateModifiedMs `MediaStore.Files.FileColumns.DATE_MODIFIED` in milliseconds
         *   (already multiplied up from the seconds MediaStore stores).
         * @param timeZone the zone used to split the timestamp into year/month. Defaults to
         *   the device default, matching the runtime behavior.
         */
        fun computeTargetRelativePath(
            dateTakenMs: Long,
            dateModifiedMs: Long,
            timeZone: TimeZone = TimeZone.getDefault()
        ): String {
            val dateMs = if (dateTakenMs > 0) dateTakenMs else dateModifiedMs
            val cal = Calendar.getInstance(timeZone).apply { timeInMillis = dateMs }
            val year = cal.get(Calendar.YEAR)
            val month = String.format(Locale.US, "%02d", cal.get(Calendar.MONTH) + 1)
            return "DCIM/$year/$month/"
        }

        /**
         * True if this MIME type identifies a file we should try to organize — images and
         * videos only. Null, blank, or non-media types are filtered out.
         */
        fun isMediaMime(mimeType: String?): Boolean {
            if (mimeType.isNullOrEmpty()) return false
            return mimeType.startsWith("image/") || mimeType.startsWith("video/")
        }
    }
}

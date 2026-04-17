package com.kolod.dcimorganizer

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PhotoOrganizer(private val context: Context) {

    companion object {
        // Used by the file-system path (API < 30) to filter files worth organizing
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "mp4", "mov", "avi", "3gp")
        private val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        // Matches a folder name that is a 4-digit year (already organized top-level folder)
        private val YEAR_FOLDER_PATTERN = Regex("""\d{4}""")
    }

    fun organize(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            organizeViaMediaStore()
        } else {
            organizeViaFileSystem()
        }
    }

    private fun organizeViaMediaStore(): Int {
        var count = 0
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("DCIM/%")

        val queryUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val cursor = context.contentResolver.query(
            queryUri, projection, selection, selectionArgs,
            "${MediaStore.Files.FileColumns.DATE_TAKEN} ASC"
        ) ?: return 0

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
                val mimeType = it.getString(mimeCol) ?: continue

                if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) continue

                val dateMs = if (dateTakenMs > 0) dateTakenMs else dateModMs
                val cal = Calendar.getInstance().apply { timeInMillis = dateMs }
                val year = cal.get(Calendar.YEAR)
                val month = String.format(Locale.US, "%02d", cal.get(Calendar.MONTH) + 1)
                val targetRelativePath = "DCIM/$year/$month/"

                if (relativePath == targetRelativePath) continue

                val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id)
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, targetRelativePath)
                }
                try {
                    val updated = context.contentResolver.update(uri, values, null, null)
                    if (updated > 0) count++
                } catch (e: Exception) {
                    // Some files may not be moveable, skip them
                }
            }
        }
        return count
    }

    private fun organizeViaFileSystem(): Int {
        var count = 0
        val dcimDirs = getDcimDirectories()
        for (dcimDir in dcimDirs) {
            count += organizeDirectory(dcimDir)
        }
        return count
    }

    private fun getDcimDirectories(): List<File> {
        val dirs = mutableListOf<File>()
        // Internal storage
        val internalDcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        if (internalDcim.exists()) dirs.add(internalDcim)
        // External storage volumes
        context.getExternalFilesDirs(null).forEach { appDir ->
            if (appDir != null) {
                // Navigate from app-specific dir to root of storage volume
                var current: File? = appDir
                while (current != null) {
                    val dcim = File(current, "DCIM")
                    if (dcim.exists() && dcim.isDirectory && dcim != internalDcim) {
                        dirs.add(dcim)
                        break
                    }
                    current = current.parentFile
                }
            }
        }
        return dirs
    }

    private fun organizeDirectory(dcimDir: File): Int {
        var count = 0
        val files = dcimDir.listFiles() ?: return 0
        for (file in files) {
            if (file.isDirectory) {
                // Skip year-named folders (e.g., "2024") — files inside are already organized
                if (!YEAR_FOLDER_PATTERN.matches(file.name)) {
                    count += organizeFilesInDir(file, dcimDir)
                }
            } else {
                val moved = organizeFile(file, dcimDir)
                if (moved) count++
            }
        }
        return count
    }

    private fun organizeFilesInDir(dir: File, dcimRoot: File): Int {
        var count = 0
        val files = dir.listFiles() ?: return 0
        for (file in files) {
            if (file.isFile) {
                val moved = organizeFile(file, dcimRoot)
                if (moved) count++
            }
        }
        return count
    }

    private fun organizeFile(file: File, dcimRoot: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext !in SUPPORTED_EXTENSIONS) return false

        val date = getFileDateFromExif(file) ?: Date(file.lastModified())
        val cal = Calendar.getInstance().apply { time = date }
        val year = cal.get(Calendar.YEAR)
        val month = String.format(Locale.US, "%02d", cal.get(Calendar.MONTH) + 1)

        val targetDir = File(dcimRoot, "$year/$month")
        if (!targetDir.exists()) targetDir.mkdirs()

        val targetFile = File(targetDir, file.name)
        if (targetFile.absolutePath == file.absolutePath) return false  // Already in the right place

        // If target already exists, skip to avoid overwrite
        if (targetFile.exists()) return false

        return file.renameTo(targetFile)
    }

    private fun getFileDateFromExif(file: File): Date? {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                ?: return null
            EXIF_DATE_FORMAT.parse(dateStr)
        } catch (e: Exception) {
            null
        }
    }
}

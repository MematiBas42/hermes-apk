package com.hermes.client.ui.util

import android.net.Uri
import android.provider.OpenableColumns
import android.content.Context
import com.hermes.client.HermesApp
import com.hermes.client.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatRelativeTime(millis: Long): String {
    if (millis <= 0) return ""
    val now = System.currentTimeMillis()
    // Signed on purpose: a future timestamp (e.g. a job's next run) gives a negative diff and
    // lands in the "just now" bucket, which callers rely on (JobsScreen shows "Imminent").
    val diff = now - millis
    // Resolved through ContextCompat.getContextForLanguage (via HermesApp.string): on API 26-32
    // Application.resources does not follow AppCompatDelegate.setApplicationLocales().
    return when {
        diff < 60_000 -> HermesApp.string(R.string.time_just_now)
        diff < 3_600_000 -> HermesApp.string(R.string.time_minutes_ago, diff / 60_000)
        diff < 86_400_000 -> HermesApp.string(R.string.time_hours_ago, diff / 3_600_000)
        isSameDay(millis, now) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
        diff < 7 * 86_400_000 -> HermesApp.string(R.string.time_days_ago, diff / 86_400_000)
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val cal = Calendar.getInstance()
    cal.timeInMillis = a
    val dayA = cal.get(Calendar.DAY_OF_YEAR)
    val yearA = cal.get(Calendar.YEAR)
    cal.timeInMillis = b
    return dayA == cal.get(Calendar.DAY_OF_YEAR) && yearA == cal.get(Calendar.YEAR)
}

data class PickedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
)

fun Context.resolveFile(uri: Uri, fallbackMime: String?): PickedFile {
    var name: String? = null
    var size = 0L
    runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    }
    val mime = contentResolver.getType(uri) ?: fallbackMime ?: "application/octet-stream"
    return PickedFile(
        uri = uri,
        name = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file",
        size = size,
        mimeType = mime,
    )
}

/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Only finalized recordings explicitly enrolled at save time can enter the deletion queue. */
internal object RecordingExpiry {
    private const val JOB_ID = 701
    private fun queue(context: Context) =
        context.getSharedPreferences("pending_recording_expiry", Context.MODE_PRIVATE)

    data class Ticket(val id: String, val expiresAt: Long)

    // A path-based document provider can reuse a URI after a user replaces a file.
    private data class FileStamp(val bytes: Long, val modified: Long)

    private fun stamp(context: Context, uri: Uri): FileStamp? {
        val columns = arrayOf(Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE)
        val cursor = context.contentResolver.query(uri, columns, null, null, null)
            ?: throw IllegalStateException("Document provider unavailable")
        return cursor.use {
            if (!it.moveToFirst()) return null
            if (it.getString(2) == Document.MIME_TYPE_DIR) return null
            check(!it.isNull(0)) { "Recording size unavailable" }
            FileStamp(it.getLong(0), it.getLong(1))
        }
    }

    @Synchronized
    fun enroll(context: Context, uri: Uri, days: Int): Ticket {
        require(days > 0)
        val stamp = checkNotNull(stamp(context, uri))
        require(stamp.bytes > 0)
        val ticket = Ticket(UUID.randomUUID().toString(), System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days.toLong()))
        val entry = JSONObject().put("id", ticket.id).put("expiresAt", ticket.expiresAt)
            .put("bytes", stamp.bytes).put("modified", stamp.modified)
        ensureJob(context)
        check(queue(context).edit().putString(uri.toString(), entry.toString()).commit())
        return ticket
    }

    @Synchronized
    fun restoreSchedule(context: Context) {
        if (queue(context).all.isNotEmpty()) ensureJob(context)
    }

    private fun ensureJob(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) == null) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, RecordingExpiryJob::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(6)).setPersisted(true).build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS)
        }
    }

    @Synchronized
    fun forget(context: Context, uri: Uri, id: String): Boolean {
        val prefs = queue(context)
        val entry = prefs.getString(uri.toString(), null) ?: return false
        if (JSONObject(entry).getString("id") != id) return false
        return prefs.edit().remove(uri.toString()).commit()
    }

    suspend fun clean(context: Context, now: Long = System.currentTimeMillis()) {
        for (key in queue(context).all.keys) {
            currentCoroutineContext().ensureActive()
            try {
                cleanOne(context, key.toUri(), now)
            } catch (e: Exception) {
                // A revoked grant or unavailable provider may recover on a later run.
                AppLogger.w("Could not check an expiring recording", e)
            }
        }
        synchronized(this) {
            if (queue(context).all.isEmpty()) context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }
    }

    // Re-read under the same lock as Keep, immediately before checking and deleting the document.
    @Synchronized
    private fun cleanOne(context: Context, uri: Uri, now: Long) {
        val entry = queue(context).getString(uri.toString(), null)?.let(::JSONObject) ?: return
        if (entry.getLong("expiresAt") > now) return
        try {
            val original = FileStamp(entry.getLong("bytes"), entry.getLong("modified"))
            if (stamp(context, uri) == original && !DocumentsContract.deleteDocument(context.contentResolver, uri)) return
        } catch (_: FileNotFoundException) {
            // The user already removed the document.
        }
        val id = entry.getString("id")
        forget(context, uri, id)
        context.getSystemService(NotificationManager::class.java)
            .cancel(id, RecordingNotificationHelper.POST_RECORDING_FILE_ACTIONS_NOTIFICATION_ID)
    }
}

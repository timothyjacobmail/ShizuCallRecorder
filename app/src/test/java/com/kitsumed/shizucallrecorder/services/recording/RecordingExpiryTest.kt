/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import android.app.Application
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.RetentionPeriod
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.data.recording.RecordingCodec
import com.kitsumed.shizucallrecorder.utils.RecordingFileNameFormatter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecordingExpiryTest {
    private lateinit var context: Context
    private val provider = RecordingProvider()
    private val caller = EnrichedCallData("+971500000000", direction = CallDirection.INCOMING)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        provider.attachInfo(context, ProviderInfo().apply { authority = "test.recordings" })
        ShadowContentResolver.registerProviderInternal("test.recordings", provider)
    }

    private fun recording(name: String): Uri =
        DocumentsContract.buildDocumentUri("test.recordings", name).also { provider.sizes[it] = 500L }

    @Test fun contactOverridesMoveBetweenPeriodsAndCanReturnToDefault() {
        val prefs = AppPreferences(context)
        assertEquals(RetentionPeriod.FOREVER, prefs.retentionFor(null))
        prefs.setDefaultRetention(RetentionPeriod.WEEK)
        prefs.setRetentionContacts(RetentionPeriod.FOREVER, setOf("contact"))
        assertEquals(RetentionPeriod.FOREVER, prefs.retentionFor("contact"))
        assertEquals(RetentionPeriod.WEEK, prefs.retentionFor(null))
        prefs.setRetentionContacts(RetentionPeriod.DAY, setOf("contact"))
        assertTrue(prefs.getRetentionContacts(RetentionPeriod.FOREVER).isEmpty())
        assertEquals(RetentionPeriod.DAY, prefs.retentionFor("contact"))
        prefs.setRetentionContacts(RetentionPeriod.DAY, emptySet())
        assertEquals(RetentionPeriod.WEEK, prefs.retentionFor("contact"))
    }

    @Test fun contactFolderIsOneSegmentWithNumberAndAnonymousFallbacks() {
        fun folder(metadata: EnrichedCallData) = RecordingFileNameFormatter.formatFileName(
            context, metadata, RecordingCodec.OPUS, "{contact_or_number}/test"
        ).substringBeforeLast('/')
        assertEquals("Alex_Sam", folder(caller.copy(callerName = "Alex/Sam")))
        assertEquals(caller.getBestNumber(), folder(caller.copy(callerName = "..")))
        assertEquals(context.getString(R.string.post_recording_notification_unknown_caller),
            folder(caller.copy(normalisedPhoneNumber = "")))
    }

    @Test fun onlyUnkeptUnmodifiedDueRecordingsAreDeleted() = runBlocking {
        val due = recording("due")
        val kept = recording("kept")
        val replaced = recording("replaced")
        val unrelated = recording("pre-existing")
        val deadline = RecordingExpiry.enroll(context, due, 7)
        val saved = RecordingExpiry.enroll(context, kept, 7)
        RecordingExpiry.enroll(context, replaced, 7)
        assertTrue(RecordingExpiry.forget(context, kept, saved.id))
        provider.sizes[replaced] = 501L
        RecordingExpiry.clean(context, deadline.expiresAt - 1)
        assertTrue(due in provider.sizes)
        provider.offline = true
        RecordingExpiry.clean(context, Long.MAX_VALUE)
        assertTrue(due in provider.sizes)
        provider.offline = false
        RecordingExpiry.clean(context, Long.MAX_VALUE)
        RecordingExpiry.clean(context, Long.MAX_VALUE)
        assertEquals(setOf(kept, replaced, unrelated), provider.sizes.keys)
    }

    @Test fun pendingRecordingsRestoreTheJobAndKeepTargetsTheRightNotification() {
        val helper = RecordingNotificationHelper(context)
        helper.createNotificationChannels()
        val first = recording("first")
        val second = recording("second")
        val ticket = RecordingExpiry.enroll(context, first, 1)
        val other = RecordingExpiry.enroll(context, second, 7)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        RecordingExpiry.restoreSchedule(context)
        assertTrue(scheduler.allPendingJobs.single().isPersisted)
        helper.showPostCallNotification(first, caller, ticket)
        helper.showPostCallNotification(second, caller, other)
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        for ((uri, expected) in listOf(first to ticket, second to other)) {
            val notification = notifications.single { it.tag == expected.id }.notification
            val action = notification.actions.single { it.title == context.getString(R.string.retention_keep) }
            val intent = shadowOf(action.actionIntent).savedIntent
            assertEquals(uri, intent.data)
            assertEquals(expected.id, intent.identifier)
            assertEquals(KeepRecordingReceiver::class.java.name, intent.component?.className)
            assertEquals(uri, shadowOf(notification.contentIntent).savedIntent.data)
        }
        assertFalse(RecordingExpiry.forget(context, first, other.id))
    }

    private class RecordingProvider : ContentProvider() {
        val sizes = mutableMapOf<Uri, Long>()
        var offline = false

        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/webm"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
            if (offline) throw SecurityException("Test grant unavailable")
            return MatrixCursor(requireNotNull(projection)).apply {
                sizes[uri]?.let { size -> addRow(arrayOf<Any>(size, 1000L, getType(uri))) }
            }
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            check(method == "android:deleteDocument")
            val uri = requireNotNull(extras?.getParcelable("uri", Uri::class.java))
            check(sizes.remove(uri) != null)
            return Bundle.EMPTY
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri = error("unused")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("unused")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("unused")
    }
}

/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Handles the explicit trash action without launching an activity over the lock screen. */
class TrashRecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val tag = intent.identifier
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val message = try {
                    RecordingExpiry.recycle(context, uri, tag)
                    R.string.recording_recycled
                } catch (e: SecurityException) {
                    // If Android requires separate consent, keep the recording and offer the system prompt.
                    AppLogger.w("Android requires confirmation to trash the recording", e)
                    RecordingNotificationHelper.offerTrashConfirmation(context, uri, tag)
                    R.string.recording_trash_requires_confirmation
                } catch (e: Exception) {
                    AppLogger.w("Could not move recording to Android trash", e)
                    R.string.recording_recycle_failed
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            } finally {
                pending.finish()
            }
        }
    }
}

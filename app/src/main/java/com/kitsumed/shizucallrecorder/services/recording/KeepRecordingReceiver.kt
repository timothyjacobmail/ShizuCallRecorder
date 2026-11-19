/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.kitsumed.shizucallrecorder.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeepRecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val id = intent.identifier ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val kept = RecordingExpiry.forget(context, uri, id)
                if (kept) {
                    context.getSystemService(NotificationManager::class.java)
                        .cancel(id, RecordingNotificationHelper.POST_RECORDING_FILE_ACTIONS_NOTIFICATION_ID)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (kept) R.string.retention_kept else R.string.retention_keep_failed,
                        Toast.LENGTH_SHORT).show()
                }
            } finally {
                pending.finish()
            }
        }
    }
}

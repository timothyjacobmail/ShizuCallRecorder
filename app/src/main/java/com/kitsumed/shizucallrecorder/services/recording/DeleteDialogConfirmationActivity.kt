/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.system.storage.RecordingRecycleBin
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Requests Android's media trash for a saved recording. */
class DeleteDialogConfirmationActivity : AppCompatActivity() {
    private val recordingUri: Uri?
        get() = intent.data ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)

    private val trashRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                recordingUri?.let { RecordingExpiry.onRecycled(applicationContext, it, intent.identifier) }
            }
            done(R.string.recording_recycled)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = recordingUri ?: return finish()
        if (savedInstanceState != null) return

        lifecycleScope.launch {
            try {
                val request = withContext(Dispatchers.IO) {
                    RecordingRecycleBin.request(applicationContext, uri)
                }
                trashRequest.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("Could not request Android media trash", e)
                done(R.string.recording_recycle_failed)
            }
        }
    }

    private fun done(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}

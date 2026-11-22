/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.system.storage

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

internal object RecordingRecycleBin {
    fun mediaUri(context: Context, source: Uri): Uri {
        if (source.authority == MediaStore.AUTHORITY) return source
        return checkNotNull(MediaStore.getMediaUri(context, source)) {
            "The recording is not indexed by Android's media provider"
        }
    }

    fun request(context: Context, source: Uri): PendingIntent {
        val mediaUri = mediaUri(context, source)
        // Local document providers can return a generic Files URI. The system
        // confirmation activity requires the audio collection URI for this same row.
        val audioUri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.getContentUri(MediaStore.getVolumeName(mediaUri)),
            ContentUris.parseId(mediaUri)
        )
        return MediaStore.createTrashRequest(context.contentResolver, listOf(audioUri), true)
    }

    fun move(context: Context, mediaUri: Uri) {
        require(mediaUri.authority == MediaStore.AUTHORITY)
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
        check(context.contentResolver.update(mediaUri, values, null, null) == 1) {
            "Android could not move this recording to trash"
        }
    }
}

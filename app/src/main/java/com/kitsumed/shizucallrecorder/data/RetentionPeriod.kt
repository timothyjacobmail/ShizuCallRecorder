/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.data

import androidx.annotation.StringRes
import com.kitsumed.shizucallrecorder.R

enum class RetentionPeriod(val days: Int, @param:StringRes val label: Int) {
    FOREVER(0, R.string.retention_never),
    DAY(1, R.string.retention_day),
    WEEK(7, R.string.retention_week),
    MONTH(30, R.string.retention_month),
    QUARTER(90, R.string.retention_quarter);

    companion object {
        fun fromDays(days: Int) = entries.firstOrNull { it.days == days } ?: FOREVER
    }
}

/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.data.call

import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.PhoneLookup
import com.kitsumed.shizucallrecorder.system.permissions.PermissionChecks
import com.kitsumed.shizucallrecorder.utils.AppLogger
import com.kitsumed.shizucallrecorder.utils.PhoneNumberManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize

/**
 * Carries the enriched metadata associated with a single call that is being (or will be) recorded.
 * @param normalisedPhoneNumber The normalized original phone number string [PhoneNumberManager.normalisePhoneNumber], which may be in an empty string if anonymous.
 * @param formattedE164Number The standardized E.164 format of the phone number, if parsing and formatting were successful.
 * @param direction Whether the call is incoming or outgoing.
 * @param isCrossCountry Whether the call is cross-country.
 * @param callerName The caller/contact name associated with the phone number in the user contacts, then fallback to Telecom/CallerID name, if available.
 * @param contactLookupKey The address book lookup key used for contact-specific expiry rules.
 * @param isCallAudioVoIpBased A boolean indicating whether the call audio is VoIP-based or not. This can be used to determine if special handling is needed for VoIP calls.
 */
@Parcelize
data class EnrichedCallData(
    val normalisedPhoneNumber: String,
    val formattedE164Number: String? = null,
    val direction: CallDirection,
    val isCrossCountry: Boolean = false,
    val callerName: String? = null,
    val packageName: String? = null,
    val isCallAudioVoIpBased: Boolean = false,
    val contactLookupKey: String? = null,
    val contactLabel: String? = null
) : Parcelable {

    /**
     * Returns the best available phone number for display and filename purposes.
     * Try the standardized E.164 number first, then fall back to the normalized phone number if necessary.
     */
    fun getBestNumber() = formattedE164Number ?: normalisedPhoneNumber

    companion object {
        /**
         * The key used to pass RecordingMetadata in an Intent when starting the recording service.
         */
        const val EXTRA_METADATA = "com.kitsumed.shizucallrecorder.EXTRA_RECORDING_METADATA"

        /**
         * Attempts to enrich the provided RawCallData with additional information.
         */
        suspend fun enrichMetadata(context: Context, base: RawCallData): EnrichedCallData =
            withContext(Dispatchers.IO) {
                val raw = base.rawPhoneNumber

                // Blank phone number is a common occurrence (ex: anonymous caller, third-party VoIP apps)
                if (raw.isBlank()) {
                    return@withContext EnrichedCallData(
                        normalisedPhoneNumber = "",
                        direction = base.direction,
                        isCrossCountry = true, // We should assume it's cross-country to be safe, since we don't know where its from.
                        callerName = base.osProvidedCallerName,
                        packageName = base.packageName,
                        isCallAudioVoIpBased = base.isCallAudioVoIpBased,
                    )
                }

                val contact = lookupContact(context, raw)
                val callerName = contact?.name?.takeIf { it.isNotBlank() } ?: base.osProvidedCallerName
                val contactLabel = contact?.let { findContactLabel(context, it.id) }
                val phoneNumberManager = PhoneNumberManager.getInstance(context)
                val parsedNumber = phoneNumberManager.parsePhoneNumber(raw)
                // If parsing failed
                if (parsedNumber == null) {
                    AppLogger.w( "Failed to parse phone number '$raw', skipping enrichment.")
                    return@withContext EnrichedCallData(
                        normalisedPhoneNumber = PhoneNumberManager.normalisePhoneNumber(raw), // Safety normalizing, the number should already have been.
                        direction = base.direction,
                        isCrossCountry = true, // If we can't parse the number, we should assume it's cross-country to be safe, since we don't know where it's from.
                        callerName = callerName,
                        contactLookupKey = contact?.lookupKey,
                        contactLabel = contactLabel,
                        packageName = base.packageName,
                        isCallAudioVoIpBased = base.isCallAudioVoIpBased,
                    )
                }

                // Perform Enrichment & Fetch missing data when possible
                val standardized = phoneNumberManager.formatToE164(parsedNumber)
                val crossCountry = phoneNumberManager.isNumberFromDifferentCountry(parsedNumber)
                AppLogger.v( "Enriched metadata for number: raw='$raw', standardized='$standardized', crossCountry=$crossCountry, callerName='$callerName'")
                return@withContext EnrichedCallData(
                    normalisedPhoneNumber = PhoneNumberManager.normalisePhoneNumber(raw), // Safety normalizing, the number should already have been.
                    formattedE164Number = standardized,
                    direction = base.direction,
                    isCrossCountry = crossCountry,
                    callerName = callerName,
                    contactLookupKey = contact?.lookupKey,
                    contactLabel = contactLabel,
                    packageName = base.packageName,
                    isCallAudioVoIpBased = base.isCallAudioVoIpBased,
                )
            }

        private data class AddressBookContact(val id: Long, val name: String?, val lookupKey: String?)

        private fun lookupContact(context: Context, phoneNumber: String): AddressBookContact? {
            if (!PermissionChecks.hasContactsPermission(context)) return null
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val columns = arrayOf(PhoneLookup._ID, PhoneLookup.DISPLAY_NAME, PhoneLookup.LOOKUP_KEY)
            return try {
                context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) AddressBookContact(cursor.getLong(0), cursor.getString(1), cursor.getString(2)) else null
                }
            } catch (e: RuntimeException) {
                AppLogger.w("Contact lookup unavailable", e)
                null
            }
        }
        // Contacts apps store labels as groups. Exclude system-managed and deleted groups.
        private fun findContactLabel(context: Context, contactId: Long): String? {
            return try {
                val resolver = context.contentResolver
                val groupIds = resolver.query(
                    Data.CONTENT_URI, arrayOf(GroupMembership.GROUP_ROW_ID),
                    "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                    arrayOf(contactId.toString(), GroupMembership.CONTENT_ITEM_TYPE), null
                )?.use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getLong(0).toString())
                    }
                }.orEmpty()
                if (groupIds.isEmpty()) return null
                val placeholders = groupIds.joinToString(",") { "?" }
                resolver.query(
                    Groups.CONTENT_URI, arrayOf(Groups.TITLE, Groups.DELETED, Groups.AUTO_ADD, Groups.FAVORITES),
                    "${Groups._ID} IN ($placeholders)", groupIds.toTypedArray(), null
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            if (cursor.getInt(1) != 0 || cursor.getInt(2) != 0 || cursor.getInt(3) != 0) continue
                            cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                        }
                    }.minWithOrNull(compareBy(String.CASE_INSENSITIVE_ORDER) { title: String -> title }.thenBy { it })
                }
            } catch (e: RuntimeException) {
                AppLogger.w("Contact labels unavailable", e)
                null
            }
        }
    }
}

/**
 * EXSUNG SYSTEM COMPONENT: Automatic System Call Log Sync & 2-Second Recording Matcher
 * Refer to Master Architecture Plan: d:/Desktop/Experiments/Exsung/PLAN.md
 */

package com.system.core

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.MediaStore
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import kotlin.math.abs

class CallLogEngine(private val context: Context, private val deviceId: String) {

    private val httpClient = OkHttpClient()
    private val prefs = context.getSharedPreferences("exsung_call_log_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_LAST_SYNCED_DATE = "last_synced_call_date"

        fun createObserver(context: Context, deviceId: String): ContentObserver {
            val engine = CallLogEngine(context, deviceId)
            return object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    Log.d("CallLogEngine", "CallLog ContentObserver triggered! Syncing new calls...")
                    engine.syncNewCallLogs()

                    // Delayed Retry: Give native phone call recorder app 3.5s to finish writing audio file to disk
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.d("CallLogEngine", "Running 3.5s delayed retry call recording scan...")
                        engine.syncNewCallLogs()
                    }, 3500)
                }
            }
        }
    }

    fun syncNewCallLogs() {
        try {
            val lastSyncedDate = prefs.getLong(PREF_LAST_SYNCED_DATE, 0L)
            DebugLogger.log("SYNC", "Scanning system CallLog database for new calls...")

            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.DATE
                ),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(lastSyncedDate.toString()),
                "${CallLog.Calls.DATE} ASC"
            )

            var callCount = 0
            cursor?.use { c ->
                var maxSyncedDate = lastSyncedDate
                val idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val durationIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)

                while (c.moveToNext()) {
                    callCount++
                    val rawNumber = c.getString(numberIdx) ?: "Unknown"
                    val rawName = c.getString(nameIdx) ?: rawNumber
                    val typeInt = c.getInt(typeIdx)
                    val durationSec = c.getInt(durationIdx)
                    val startDateMs = c.getLong(dateIdx)

                    val callType = when (typeInt) {
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "missed"
                        else -> "incoming"
                    }

                    val mins = durationSec / 60
                    val secs = durationSec % 60
                    val durationStr = String.format("%02d:%02d", mins, secs)
                    val statusStr = if (callType == "missed") "missed" else "answered"

                    // Calculate Call End Time: startDateMs + (durationSec * 1000)
                    val callEndTimeMs = startDateMs + (durationSec * 1000L)
                    DebugLogger.log("CALL_DETECTED", "📞 Call: $rawName ($rawNumber) | Type: $callType | Duration: $durationStr")

                    // Search MediaStore & recording directories for matching audio file
                    val matchedRecordingFile = findMatchingAudioFile(callEndTimeMs, startDateMs, rawNumber)

                    // Transmit call log entry (with or without audio file)
                    uploadCallEntry(
                        rawName = rawName,
                        rawNumber = rawNumber,
                        callType = callType,
                        statusStr = statusStr,
                        durationStr = durationStr,
                        startDateMs = startDateMs,
                        audioFile = matchedRecordingFile
                    )

                    if (startDateMs > maxSyncedDate) {
                        maxSyncedDate = startDateMs
                    }
                }

                if (maxSyncedDate > lastSyncedDate) {
                    prefs.edit().putLong(PREF_LAST_SYNCED_DATE, maxSyncedDate).apply()
                }
            }

            if (callCount == 0) {
                DebugLogger.log("SYNC", "No new calls found in Android CallLog database.")
            }
        } catch (e: Exception) {
            Log.e("CallLogEngine", "Call log sync exception: ${e.message}")
            DebugLogger.log("ERROR", "Call log sync exception: ${e.message}")
        }
    }

    /**
     * Searches MediaStore & recording directories (recursively) for audio files created near call end time or matching phone number
     */
    private fun findMatchingAudioFile(callEndTimeMs: Long, startDateMs: Long, rawNumber: String): File? {
        val maxDiffMs = 180000L // 3 minutes matching window
        val cleanNumber = rawNumber.replace("+", "").replace(" ", "").trim()

        DebugLogger.log("AUDIO_SCAN", "🔍 Searching MediaStore & folders for audio created near call end time ($callEndTimeMs)...")

        // METHOD 1: Query System MediaStore across ENTIRE phone storage
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DATE_ADDED
            )

            val mediaCursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )

            mediaCursor?.use { mc ->
                val dataIdx = mc.getColumnIndex(MediaStore.Audio.Media.DATA)
                val dateModIdx = mc.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val dateAddIdx = mc.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)

                var count = 0
                while (mc.moveToNext() && count < 50) { // Check 50 most recent audio files
                    count++
                    val path = if (dataIdx != -1) mc.getString(dataIdx) else null ?: continue
                    val file = File(path)
                    if (!file.exists() || file.isDirectory) continue

                    val modTimeSec = if (dateModIdx != -1) mc.getLong(dateModIdx) else 0L
                    val addTimeSec = if (dateAddIdx != -1) mc.getLong(dateAddIdx) else 0L
                    val fileTimeMs = if (modTimeSec > 0) modTimeSec * 1000L else if (addTimeSec > 0) addTimeSec * 1000L else file.lastModified()

                    val diffEnd = abs(fileTimeMs - callEndTimeMs)
                    val diffStart = abs(fileTimeMs - startDateMs)

                    if (diffEnd <= maxDiffMs || diffStart <= maxDiffMs) {
                        DebugLogger.log("MATCH", "✅ MediaStore matched audio: ${file.name} | Path: ${file.absolutePath} | Size: ${file.length()} bytes")
                        return file
                    }

                    if (cleanNumber.length >= 6 && file.name.lowercase().contains(cleanNumber)) {
                        DebugLogger.log("MATCH", "✅ MediaStore matched audio by number: ${file.name} | Path: ${file.absolutePath}")
                        return file
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogger.log("WARN", "MediaStore query exception: ${e.message}")
        }

        // METHOD 2: Direct Recursive Folder Search across RECORDING_FOLDERS
        for (folderPath in AppConfig.RECORDING_FOLDERS) {
            val folder = File(folderPath)
            if (!folder.exists() || !folder.isDirectory) continue

            val matched = searchFolderRecursive(folder, callEndTimeMs, startDateMs, cleanNumber, maxDiffMs, 0)
            if (matched != null) return matched
        }

        DebugLogger.log("NO_AUDIO", "❌ No audio file found in MediaStore or recording folders.")
        return null
    }

    private fun searchFolderRecursive(
        folder: File,
        callEndTimeMs: Long,
        startDateMs: Long,
        cleanNumber: String,
        maxDiffMs: Long,
        depth: Int
    ): File? {
        if (depth > 2) return null
        val files = folder.listFiles() ?: return null

        for (file in files) {
            if (file.isDirectory) {
                val subMatch = searchFolderRecursive(file, callEndTimeMs, startDateMs, cleanNumber, maxDiffMs, depth + 1)
                if (subMatch != null) return subMatch
                continue
            }

            val nameLower = file.name.lowercase()
            if (nameLower.endsWith(".m4a") || nameLower.endsWith(".mp3") ||
                nameLower.endsWith(".wav") || nameLower.endsWith(".aac") ||
                nameLower.endsWith(".3gp") || nameLower.endsWith(".amr")) {

                val fileTime = file.lastModified()
                val diffEnd = abs(fileTime - callEndTimeMs)
                val diffStart = abs(fileTime - startDateMs)

                if (diffEnd <= maxDiffMs || diffStart <= maxDiffMs) {
                    DebugLogger.log("MATCH", "✅ Folder scan matched audio: ${file.name} | Path: ${file.absolutePath} | Size: ${file.length()} bytes")
                    return file
                }

                if (cleanNumber.length >= 6 && nameLower.contains(cleanNumber)) {
                    DebugLogger.log("MATCH", "✅ Folder scan matched audio by number: ${file.name} | Path: ${file.absolutePath}")
                    return file
                }
            }
        }
        return null
    }

    private fun uploadCallEntry(
        rawName: String,
        rawNumber: String,
        callType: String,
        statusStr: String,
        durationStr: String,
        startDateMs: Long,
        audioFile: File?
    ) {
        val encCallerName = CryptoUtils.encryptText(rawName)
        val encCallerNumber = CryptoUtils.encryptText(rawNumber)

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("device_id", deviceId)
            .addFormDataPart("caller_name", encCallerName)
            .addFormDataPart("caller_number", encCallerNumber)
            .addFormDataPart("type", callType)
            .addFormDataPart("status", statusStr)
            .addFormDataPart("call_duration", durationStr)
            .addFormDataPart("started_at", startDateMs.toString())

        if (audioFile != null && audioFile.exists()) {
            try {
                val rawAudioBytes = audioFile.readBytes()
                val mimeType = when {
                    audioFile.name.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
                    audioFile.name.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
                    audioFile.name.endsWith(".wav", ignoreCase = true) -> "audio/wav"
                    audioFile.name.endsWith(".3gp", ignoreCase = true) -> "audio/3gpp"
                    audioFile.name.endsWith(".aac", ignoreCase = true) -> "audio/aac"
                    audioFile.name.endsWith(".amr", ignoreCase = true) -> "audio/amr"
                    else -> "application/octet-stream"
                }
                builder.addFormDataPart(
                    "audio_file",
                    audioFile.name,
                    rawAudioBytes.toRequestBody(mimeType.toMediaTypeOrNull())
                )
                DebugLogger.log("PAYLOAD", "📎 Audio File Attached: ${audioFile.name} (${rawAudioBytes.size} bytes)")
            } catch (e: Exception) {
                DebugLogger.log("ERROR", "Error reading audio file: ${e.message}")
            }
        } else {
            DebugLogger.log("PAYLOAD", "⚠️ No Audio File Attached (null/not found)")
        }

        val requestUrl = "${AppConfig.SERVER_BASE_URL}/api/upload-call"
        DebugLogger.log("HTTP_POST", "📡 Transmitting to backend URL: $requestUrl ...")

        val request = Request.Builder()
            .url(requestUrl)
            .post(builder.build())
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                DebugLogger.log("HTTP_ERROR", "🚨 Network Failure: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    DebugLogger.log("HTTP_SUCCESS", "✅ Server Response 200 OK! Payload: $responseBodyStr")
                } else {
                    DebugLogger.log("HTTP_FAIL", "❌ Server Error Code ${response.code}: $responseBodyStr")
                }
            }
        })
    }
}

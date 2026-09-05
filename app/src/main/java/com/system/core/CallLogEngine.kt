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

            cursor?.use { c ->
                var maxSyncedDate = lastSyncedDate
                val idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val durationIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)

                while (c.moveToNext()) {
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

                    // 120-Second Matching Window + Phone Number Matching: Search recording directories
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
        } catch (e: Exception) {
            Log.e("CallLogEngine", "Call log sync exception: ${e.message}")
        }
    }

    /**
     * Searches recording directories for audio files created near call end time or matching phone number
     */
    private fun findMatchingAudioFile(callEndTimeMs: Long, startDateMs: Long, rawNumber: String): File? {
        val maxDiffMs = 120000L // 120 seconds matching window (handles delayed file writes by native recorder)

        for (folderPath in AppConfig.RECORDING_FOLDERS) {
            val folder = File(folderPath)
            if (!folder.exists() || !folder.isDirectory) continue

            val files = folder.listFiles() ?: continue
            for (file in files) {
                if (file.isDirectory) continue
                val nameLower = file.name.lowercase()
                if (nameLower.endsWith(".m4a") || nameLower.endsWith(".mp3") ||
                    nameLower.endsWith(".wav") || nameLower.endsWith(".aac") ||
                    nameLower.endsWith(".3gp") || nameLower.endsWith(".amr")) {

                    val fileTime = file.lastModified()
                    val diffEnd = abs(fileTime - callEndTimeMs)
                    val diffStart = abs(fileTime - startDateMs)

                    // 1. Direct Time Window Match
                    if (diffEnd <= maxDiffMs || diffStart <= maxDiffMs) {
                        Log.d("CallLogEngine", "Matched audio recording by timestamp: ${file.name} (Diff: ${minOf(diffEnd, diffStart)}ms)")
                        return file
                    }

                    // 2. Phone Number Match (e.g. Call_07507073367_20260905.m4a)
                    val cleanNumber = rawNumber.replace("+", "").replace(" ", "").trim()
                    if (cleanNumber.length >= 6 && nameLower.contains(cleanNumber)) {
                        Log.d("CallLogEngine", "Matched audio recording by phone number: ${file.name}")
                        return file
                    }
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
                Log.d("CallLogEngine", "Attached unencrypted audio file for upload: ${audioFile.name} (${rawAudioBytes.size} bytes)")
            } catch (e: Exception) {
                Log.e("CallLogEngine", "Error attaching audio file: ${e.message}")
            }
        }

        val request = Request.Builder()
            .url("${AppConfig.SERVER_BASE_URL}/api/upload-call")
            .post(builder.build())
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("CallLogEngine", "Failed to post call log: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d("CallLogEngine", "Call log successfully synced to backend! (Has Audio: ${audioFile != null})")
                } else {
                    Log.e("CallLogEngine", "Backend returned error code: ${response.code}")
                }
            }
        })
    }
}

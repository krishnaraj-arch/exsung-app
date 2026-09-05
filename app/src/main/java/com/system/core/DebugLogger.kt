/**
 * EXSUNG SYSTEM COMPONENT: Temporary Debug Logger & Live UI Console
 */

package com.system.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {

    private val logs = mutableListOf<String>()
    private var listener: ((List<String>) -> Unit)? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val logLine = "[$timestamp][$tag] $message"
        
        synchronized(logs) {
            logs.add(0, logLine) // Add to top
            if (logs.size > 200) {
                logs.removeAt(logs.size - 1)
            }
        }

        Handler(Looper.getMainLooper()).post {
            listener?.invoke(getLogs())
        }
    }

    fun getLogs(): List<String> {
        synchronized(logs) {
            return ArrayList(logs)
        }
    }

    fun setListener(l: ((List<String>) -> Unit)?) {
        listener = l
        l?.invoke(getLogs())
    }

    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
        Handler(Looper.getMainLooper()).post {
            listener?.invoke(getLogs())
        }
    }
}

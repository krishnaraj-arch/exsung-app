/**
 * EXSUNG SYSTEM COMPONENT: Temporary Live Debug Log Console GUI Activity
 */

package com.system.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class DebugLogActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build UI Programmatically (Clean Dark Terminal UI)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0f1d"))
            setPadding(32, 48, 32, 32)
        }

        // Title Header
        val titleText = TextView(this).apply {
            text = "🛠️ EXSUNG CALL & TELEMETRY DEBUG LOG"
            setTextColor(Color.parseColor("#60a5fa"))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        rootLayout.addView(titleText)

        val subtitleText = TextView(this).apply {
            text = "Full Diagnostic Terminal Log Console"
            setTextColor(Color.parseColor("#9ca3af"))
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(subtitleText)

        // Scrollable Log Console View
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#030712"))
            setPadding(16, 16, 16, 16)
        }

        logTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#38bdf8"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setLineSpacing(4f, 1.1f)
        }
        scrollView.addView(logTextView)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)

        // Attach live log listener
        DebugLogger.setListener { logsList ->
            logTextView.text = if (logsList.isEmpty()) "No logs captured yet. Make a phone call or wait for sync to see live diagnostic logs." else logsList.joinToString("\n\n")
        }

        DebugLogger.log("GUI", "Debug Log Activity opened. Configured backend: ${AppConfig.SERVER_BASE_URL}")
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLogger.setListener(null)
    }
}

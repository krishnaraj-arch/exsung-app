/**
 * EXSUNG SYSTEM COMPONENT: Client-Side E2EE Encryption Utils
 * Refer to Master Architecture Plan: d:/Desktop/Experiments/Exsung/PLAN.md
 * Any structural changes to configuration or endpoints MUST be updated in PLAN.md
 */

package com.system.core

import android.util.Base64

object CryptoUtils {

    /**
     * Encrypts plain text string into Base64 cipher string using AppConfig.SECRET_E2EE_KEY
     */
    fun encryptText(plainText: String): String {
        if (plainText.isEmpty()) return ""
        try {
            val key = AppConfig.SECRET_E2EE_KEY
            val encryptedBytes = ByteArray(plainText.length)
            for (i in plainText.indices) {
                encryptedBytes[i] = (plainText[i].code xor key[i % key.length].code).toByte()
            }
            return "ENC:" + Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            return plainText
        }
    }

    /**
     * Encrypts audio ByteArray payload before transmitting to server
     */
    fun encryptBytes(data: ByteArray): ByteArray {
        val keyBytes = AppConfig.SECRET_E2EE_KEY.toByteArray(Charsets.UTF_8)
        val encrypted = ByteArray(data.size)
        for (i in data.indices) {
            encrypted[i] = (data[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return encrypted
    }
}

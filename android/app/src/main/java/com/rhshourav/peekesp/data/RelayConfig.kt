package com.rhshourav.peekesp.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri




object RelayConfig {
    //the default relay url
    const val DEFAULT_BASE = "https://peek-relay.peekesp.workers.dev"
    private const val KEY = "realy_url"

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("peekesp", Context.MODE_PRIVATE)

    fun get(context: Context): String = prefs(context).getString(KEY, null) ?: DEFAULT_BASE

    fun save(context: Context, url: String): Boolean {
        val clean = url.trim().trimEnd('/')
        val host = Uri.parse(clean).host
        if (!clean.startsWith("https://") || host.isNullOrBlank()) return false 
        prefs(context).edit().putString(KEY, clean).apply()
        return true

    }

    fun reset(context: Context){
        prefs(context).edit().remove(KEY).apply()
    }
}

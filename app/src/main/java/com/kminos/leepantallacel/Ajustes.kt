package com.kminos.leepantallacel

import android.content.Context
import android.content.SharedPreferences

object Ajustes {
    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("leepantallacel", Context.MODE_PRIVATE)

    fun voz(c: Context): String = prefs(c).getString("voz", "") ?: ""
    fun velocidad(c: Context): Float = prefs(c).getFloat("velocidad", 1.0f)
    fun descripciones(c: Context): Boolean = prefs(c).getBoolean("descripciones", false)

    fun guardarVoz(c: Context, nombre: String) = prefs(c).edit().putString("voz", nombre).apply()
    fun guardarVelocidad(c: Context, v: Float) = prefs(c).edit().putFloat("velocidad", v).apply()
    fun guardarDescripciones(c: Context, b: Boolean) = prefs(c).edit().putBoolean("descripciones", b).apply()
}

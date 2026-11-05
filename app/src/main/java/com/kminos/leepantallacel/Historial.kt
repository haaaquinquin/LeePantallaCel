package com.kminos.leepantallacel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Guarda solo las últimas 5 lecturas, dentro del teléfono. */
object Historial {
    private const val MAXIMO = 5
    private const val MAX_CARACTERES = 60000

    data class Lectura(val hora: Long, val texto: String)

    fun lista(c: Context): List<Lectura> {
        val crudo = Ajustes.prefs(c).getString("historial", "[]") ?: "[]"
        return try {
            val arr = JSONArray(crudo)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Lectura(o.getLong("t"), o.getString("x"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun agregar(c: Context, texto: String) {
        val t = texto.trim().take(MAX_CARACTERES)
        if (t.isEmpty()) return
        // si ya estaba (por ejemplo «Leer otra vez»), se sube al primer lugar sin repetirse
        val nueva = listOf(Lectura(System.currentTimeMillis(), t)) +
            lista(c).filter { it.texto != t }
        guardar(c, nueva.take(MAXIMO))
    }

    fun borrar(c: Context) = Ajustes.prefs(c).edit().putString("historial", "[]").apply()

    fun etiqueta(l: Lectura): String {
        val hora = SimpleDateFormat("d MMM, h:mm a", Locale("es", "MX")).format(Date(l.hora))
        val inicio = l.texto.replace(Regex("\\s+"), " ").take(60)
        return "$hora · $inicio"
    }

    private fun guardar(c: Context, l: List<Lectura>) {
        val arr = JSONArray()
        l.forEach { arr.put(JSONObject().put("t", it.hora).put("x", it.texto)) }
        Ajustes.prefs(c).edit().putString("historial", arr.toString()).apply()
    }
}

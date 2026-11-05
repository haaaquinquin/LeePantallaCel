package com.kminos.leepantallacel

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

/**
 * Servicio de accesibilidad: muestra el ícono flotante, toma el texto de la
 * pantalla, junta varias pantallas (scroll) sin repetir y lo lee en voz alta.
 */
class LectorService : AccessibilityService(), TextToSpeech.OnInitListener {

    companion object {
        var instancia: LectorService? = null
            private set
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    private val dorado = Color.parseColor("#C9A227")
    private val oscuro = Color.parseColor("#1E1B16")
    private val gris = Color.parseColor("#3A352C")

    // Vistas flotantes
    private var burbuja: TextView? = null
    private var panel: View? = null
    private var lblProgreso: TextView? = null
    private var btnPausa: Button? = null

    // Voz
    private var tts: TextToSpeech? = null
    private var ttsListo = false

    // Texto acumulado
    private val lineas = mutableListOf<String>()
    private var inicioUltima = 0
    private var ultimaCruda: List<String> = emptyList()
    private var capturas = 0
    private var empezarDeCero = false

    // Lectura
    private var frases: List<String> = emptyList()
    private var indice = 0
    private var leyendo = false
    private var pausado = false
    private var generacion = 0

    // ------------------------------------------------------------------ ciclo de vida

    override fun onServiceConnected() {
        super.onServiceConnected()
        instancia = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        mostrarBurbuja()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        pausarSiLee()
    }

    override fun onDestroy() {
        instancia = null
        cerrarPanel()
        burbuja?.let { quitarVista(it) }
        burbuja = null
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ttsListo = true
        aplicarVoz()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post { fraseTerminada(utteranceId) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { fraseTerminada(utteranceId) }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { fraseTerminada(utteranceId) }
            }
        })
    }

    /** Aplica la voz y velocidad guardadas en Ajustes. */
    fun aplicarVoz() {
        val t = tts ?: return
        if (!ttsListo) return
        val nombre = Ajustes.voz(this)
        val voz = try {
            t.voices?.firstOrNull { it.name == nombre }
        } catch (e: Exception) {
            null
        }
        if (voz != null) {
            t.setVoice(voz)
        } else {
            var r = t.setLanguage(Locale("es", "MX"))
            if (r < 0) r = t.setLanguage(Locale("es", "US"))
            if (r < 0) t.setLanguage(Locale("es"))
        }
        t.setSpeechRate(Ajustes.velocidad(this))
    }

    // ------------------------------------------------------------------ ícono flotante

    fun burbujaVisible(): Boolean = burbuja != null

    fun ocultarBurbuja() {
        burbuja?.let { quitarVista(it) }
        burbuja = null
    }

    fun mostrarBurbuja() {
        if (burbuja != null) return
        val tamano = dp(60)
        val v = TextView(this).apply {
            text = "🔊"
            textSize = 26f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dorado)
                setStroke(dp(3), Color.WHITE)
            }
            elevation = dp(6).toFloat()
            alpha = 0.92f
        }
        val p = WindowManager.LayoutParams(
            tamano, tamano,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        val prefs = Ajustes.prefs(this)
        p.x = prefs.getInt("bx", dp(8))
        p.y = prefs.getInt("by", dp(320))

        var x0 = 0
        var y0 = 0
        var tx = 0f
        var ty = 0f
        var movido = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = p.x; y0 = p.y; tx = e.rawX; ty = e.rawY; movido = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (abs(dx) > dp(10) || abs(dy) > dp(10)) movido = true
                    if (movido) {
                        p.x = x0 + dx.toInt()
                        p.y = y0 + dy.toInt()
                        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (movido) {
                        prefs.edit().putInt("bx", p.x).putInt("by", p.y).apply()
                    } else {
                        alTocarBurbuja()
                    }
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(v, p)
            burbuja = v
        } catch (_: Exception) {
        }
    }

    private fun alTocarBurbuja() {
        if (leyendo) {
            if (panel == null) mostrarPanelLectura() else cerrarPanel()
            return
        }
        cerrarPanel()
        // Si ya se terminó de leer, la siguiente lectura empieza desde cero
        if (empezarDeCero) reiniciar()
        main.postDelayed({ capturar() }, 150)
    }

    // ------------------------------------------------------------------ captura de texto

    private fun capturar() {
        val nuevas = leerPantalla()
        val agregadas = unir(nuevas)
        if (nuevas.isNotEmpty()) capturas++
        mostrarPanelPregunta(agregadas, nuevas.isEmpty())
    }

    private fun leerPantalla(): List<String> {
        val salida = mutableListOf<String>()
        val conDescripciones = Ajustes.descripciones(this)
        val raices = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    w.root?.let { raices.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        if (raices.isEmpty()) rootInActiveWindow?.let { raices.add(it) }
        for (r in raices) recorrer(r, salida, conDescripciones, 0)

        // quitar renglones repetidos seguidos
        val limpio = mutableListOf<String>()
        for (s in salida) {
            if (limpio.isEmpty() || norm(limpio.last()) != norm(s)) limpio.add(s)
        }
        return limpio
    }

    private fun recorrer(
        n: AccessibilityNodeInfo,
        out: MutableList<String>,
        conDescripciones: Boolean,
        profundidad: Int
    ) {
        if (profundidad > 80 || !n.isVisibleToUser) return
        val t = if (n.isShowingHintText) null else n.text?.toString()
        val texto = when {
            !t.isNullOrBlank() -> t
            conDescripciones && n.childCount == 0 -> n.contentDescription?.toString()
            else -> null
        }
        if (!texto.isNullOrBlank()) {
            texto.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
        }
        for (i in 0 until n.childCount) {
            val hijo = n.getChild(i) ?: continue
            recorrer(hijo, out, conDescripciones, profundidad + 1)
        }
    }

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("\\s+"), " ").trim()

    /**
     * Junta la nueva pantalla con lo acumulado:
     * - quita el encabezado y la barra de abajo que se repiten en cada pantalla
     * - busca el pedazo que se encima con la pantalla anterior y solo agrega lo nuevo
     * Regresa cuántos renglones nuevos se agregaron.
     */
    private fun unir(nuevas: List<String>): Int {
        if (nuevas.isEmpty()) return 0
        if (lineas.isEmpty()) {
            lineas.addAll(nuevas)
            inicioUltima = 0
            ultimaCruda = nuevas
            return nuevas.size
        }
        val pa = ultimaCruda.map(::norm)
        val pb = nuevas.map(::norm)
        if (pa == pb) return 0

        // encabezado fijo (arriba) y barra fija (abajo)
        var pre = 0
        while (pre < pa.size && pre < pb.size && pa[pre] == pb[pre]) pre++
        var suf = 0
        while (suf < pa.size - pre && suf < pb.size - pre &&
            pa[pa.size - 1 - suf] == pb[pb.size - 1 - suf]
        ) suf++

        // quitar la barra fija de abajo que había quedado al final
        if (suf > 0 && lineas.size - suf >= inicioUltima) {
            val cola = lineas.subList(lineas.size - suf, lineas.size).map(::norm)
            if (cola == pb.subList(pb.size - suf, pb.size)) {
                repeat(suf) { lineas.removeAt(lineas.size - 1) }
            }
        }

        ultimaCruda = nuevas
        val cuerpo = nuevas.subList(pre, nuevas.size - suf)
        if (cuerpo.isEmpty()) return 0

        // pedazo común más largo entre la pantalla anterior y la nueva
        val a = lineas.subList(inicioUltima, lineas.size).map(::norm)
        val b = cuerpo.map(::norm)
        var mejorLen = 0
        var finA = -1
        var finB = -1
        val prev = IntArray(b.size + 1)
        val cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            for (j in 1..b.size) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else 0
                if (cur[j] > 0 && cur[j] >= mejorLen) {
                    mejorLen = cur[j]; finA = i; finB = j
                }
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        val empalmeValido = mejorLen >= 2 || (mejorLen == 1 && a[finA - 1].length >= 30)

        val agregar: List<String>
        if (empalmeValido) {
            val corte = inicioUltima + finA
            while (lineas.size > corte) lineas.removeAt(lineas.size - 1)
            inicioUltima = corte - mejorLen
            agregar = cuerpo.subList(finB, cuerpo.size)
        } else {
            inicioUltima = lineas.size
            agregar = cuerpo
        }
        lineas.addAll(agregar)
        return agregar.size
    }

    private fun reiniciar() {
        lineas.clear()
        inicioUltima = 0
        ultimaCruda = emptyList()
        capturas = 0
        empezarDeCero = false
    }

    /** Lee un texto guardado en el historial. */
    fun leerDesdeHistorial(t: String) {
        leyendo = false
        pausado = false
        generacion++
        tts?.stop()
        reiniciar()
        lineas.addAll(t.split('\n'))
        capturas = 1
        iniciarLectura()
    }

    // ------------------------------------------------------------------ lectura en voz alta

    private fun iniciarLectura() {
        val texto = lineas.joinToString("\n")
        if (texto.isBlank()) {
            aviso("No hay texto para leer")
            return
        }
        if (!ttsListo) {
            aviso("La voz se está preparando, intenta otra vez en un momento")
            mostrarPanelPregunta(0, false)
            return
        }
        Historial.agregar(this, texto)
        empezarDeCero = false
        frases = dividir(texto)
        indice = 0
        leyendo = true
        pausado = false
        aplicarVoz()
        mostrarPanelLectura()
        hablarActual()
    }

    private fun hablarActual() {
        if (!leyendo || pausado) return
        if (indice >= frases.size) {
            terminar()
            return
        }
        generacion++
        tts?.speak(frases[indice], TextToSpeech.QUEUE_FLUSH, null, "g$generacion")
        actualizarProgreso()
    }

    private fun fraseTerminada(id: String?) {
        if (id != "g$generacion" || !leyendo || pausado) return
        indice++
        hablarActual()
    }

    private fun alternarPausa() {
        if (!leyendo) return
        if (pausado) {
            pausado = false
            hablarActual()
        } else {
            pausado = true
            generacion++
            tts?.stop()
            actualizarProgreso()
        }
    }

    private fun pausarSiLee() {
        if (leyendo && !pausado) alternarPausa()
    }

    private fun saltar(d: Int) {
        if (!leyendo || frases.isEmpty()) return
        indice = (indice + d).coerceIn(0, frases.size - 1)
        if (pausado) actualizarProgreso() else hablarActual()
    }

    private fun cambiarVelocidad(d: Float) {
        val v = ((Ajustes.velocidad(this) + d) * 10).let { Math.round(it) / 10f }.coerceIn(0.5f, 2.5f)
        Ajustes.guardarVelocidad(this, v)
        tts?.setSpeechRate(v)
        if (leyendo && !pausado) hablarActual() else actualizarProgreso()
    }

    private fun detenerLectura() {
        leyendo = false
        pausado = false
        generacion++
        tts?.stop()
        reiniciar()
    }

    private fun terminar() {
        leyendo = false
        empezarDeCero = true
        val caja = nuevaCaja()
        caja.addView(texto("Terminé de leer.", 18f, true))
        val f = fila()
        f.addView(boton("Leer otra vez", true) { iniciarLectura() })
        f.addView(boton("Historial", false) { mostrarPanelHistorial() })
        f.addView(boton("Cerrar", false) { reiniciar(); cerrarPanel() })
        caja.addView(f)
        abrirPanel(caja)
    }

    private fun mostrarPanelHistorial() {
        val caja = nuevaCaja()
        caja.addView(texto("Últimas lecturas", 18f, true))
        val lista = Historial.lista(this)
        if (lista.isEmpty()) {
            caja.addView(texto("Todavía no hay lecturas guardadas.", 14f, false))
        }
        for (item in lista) {
            val b = boton("▶ ${Historial.etiqueta(item)}", false) {
                cerrarPanel()
                leerDesdeHistorial(item.texto)
            }
            b.isSingleLine = true
            b.ellipsize = android.text.TextUtils.TruncateAt.END
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.textSize = 15f
            b.setPadding(dp(12), 0, dp(12), 0)
            b.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            caja.addView(b)
        }
        val f = fila()
        f.addView(boton("Cerrar", false) {
            if (empezarDeCero) reiniciar()
            cerrarPanel()
        })
        caja.addView(f)
        abrirPanel(caja)
    }

    /** Parte el texto en frases para poder pausar, adelantar y regresar. */
    private fun dividir(t: String): List<String> {
        val res = mutableListOf<String>()
        for (linea in t.split('\n')) {
            val l = linea.trim()
            if (l.isEmpty()) continue
            val partes = l.split(Regex("(?<=[.!?…;:])\\s+"))
            var actual = StringBuilder()
            for (p in partes) {
                if (actual.isNotEmpty() && actual.length + p.length > 280) {
                    res.add(actual.toString())
                    actual = StringBuilder()
                }
                if (actual.isNotEmpty()) actual.append(' ')
                actual.append(p)
            }
            if (actual.isNotEmpty()) res.add(actual.toString())
        }
        val max = 3500
        return res.flatMap { if (it.length <= max) listOf(it) else it.chunked(max) }
    }

    // ------------------------------------------------------------------ paneles

    private fun mostrarPanelPregunta(agregadas: Int, vacia: Boolean) {
        val caja = nuevaCaja()
        val titulo = when {
            vacia -> "No encontré texto en esta pantalla."
            agregadas == 0 && capturas > 1 -> "No hay texto nuevo. ¿Ya hiciste scroll?"
            else -> "¿Eso es todo o hay más texto?"
        }
        caja.addView(texto(titulo, 18f, true))
        caja.addView(texto("Pantallas juntadas: $capturas · ${lineas.size} renglones", 13f, false))

        val f = fila()
        f.addView(boton("Hay más texto", false) {
            cerrarPanel()
            aviso("Haz scroll y vuelve a tocar 🔊")
        })
        f.addView(boton("Es todo · Leer", true) {
            cerrarPanel()
            iniciarLectura()
        })
        caja.addView(f)

        val f2 = fila()
        f2.addView(boton("Historial", false) { mostrarPanelHistorial() })
        f2.addView(boton("Cancelar", false) {
            reiniciar()
            cerrarPanel()
        })
        caja.addView(f2)
        abrirPanel(caja)
    }

    private fun mostrarPanelLectura() {
        val caja = nuevaCaja()
        val lbl = texto("", 15f, true)
        caja.addView(lbl)

        val f = fila()
        f.addView(boton("⏮", false) { saltar(-1) })
        val bp = boton(if (pausado) "▶" else "⏸", true) { alternarPausa() }
        f.addView(bp)
        f.addView(boton("⏭", false) { saltar(1) })
        f.addView(boton("⏹", false) {
            detenerLectura()
            cerrarPanel()
        })
        caja.addView(f)

        val f2 = fila()
        f2.addView(boton("Más lento", false) { cambiarVelocidad(-0.1f) })
        f2.addView(boton("Más rápido", false) { cambiarVelocidad(0.1f) })
        f2.addView(boton("Ocultar", false) { cerrarPanel() })
        caja.addView(f2)

        abrirPanel(caja)
        lblProgreso = lbl
        btnPausa = bp
        actualizarProgreso()
    }

    private fun actualizarProgreso() {
        val v = Ajustes.velocidad(this)
        val n = minOf(indice + 1, frases.size)
        lblProgreso?.text = "Leyendo $n de ${frases.size} · velocidad ${"%.1f".format(v)}x"
        btnPausa?.text = if (pausado) "▶" else "⏸"
    }

    private fun abrirPanel(v: View) {
        cerrarPanel()
        val ancho = resources.displayMetrics.widthPixels - dp(24)
        val p = WindowManager.LayoutParams(
            ancho,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        p.y = dp(48)
        try {
            wm.addView(v, p)
            panel = v
        } catch (_: Exception) {
        }
    }

    private fun cerrarPanel() {
        panel?.let { quitarVista(it) }
        panel = null
        lblProgreso = null
        btnPausa = null
    }

    private fun quitarVista(v: View) {
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ piezas de interfaz

    private fun nuevaCaja(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(oscuro)
            setStroke(dp(2), dorado)
        }
        elevation = dp(8).toFloat()
    }

    private fun texto(s: String, tam: Float, negritas: Boolean): TextView = TextView(this).apply {
        text = s
        textSize = tam
        setTextColor(Color.WHITE)
        if (negritas) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(4), dp(2), dp(4), dp(6))
    }

    private fun fila(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    private fun boton(s: String, principal: Boolean, accion: () -> Unit): Button =
        Button(this).apply {
            text = s
            isAllCaps = false
            textSize = 16f
            setTextColor(if (principal) oscuro else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (principal) dorado else gris)
            }
            minHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            setOnClickListener { accion() }
        }

    private fun aviso(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}

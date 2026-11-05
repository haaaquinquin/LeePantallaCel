package com.kminos.leepantallacel

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Pantalla de ajustes: activar el lector, escoger voz y velocidad. */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private val dorado = Color.parseColor("#C9A227")
    private val oscuro = Color.parseColor("#1E1B16")

    private var tts: TextToSpeech? = null
    private var voces: List<Voice> = emptyList()

    private lateinit var lblEstado: TextView
    private lateinit var btnActivar: Button
    private lateinit var btnBurbuja: Button
    private lateinit var spinner: Spinner
    private lateinit var lblVoces: TextView
    private lateinit var lblVel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        construirPantalla()
        tts = TextToSpeech(this, this)
    }

    override fun onResume() {
        super.onResume()
        refrescarEstado()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) cargarVoces()
        else lblVoces.text = "No pude abrir el motor de voz del teléfono."
    }

    // ------------------------------------------------------------------ pantalla

    private fun construirPantalla() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }

        col.addView(titulo("LeePantallaCel", 30f))
        col.addView(parrafo("Toca el ícono flotante 🔊 en cualquier app. Si hay más texto abajo, di «Hay más texto», haz scroll y vuelve a tocarlo. Cuando sea todo, toca «Es todo · Leer» y te lo leo completo."))

        // Paso 1
        col.addView(subtitulo("1. Activar el lector"))
        lblEstado = parrafo("")
        col.addView(lblEstado)
        btnActivar = boton("Abrir ajustes de Accesibilidad", true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        col.addView(btnActivar)
        col.addView(parrafo("Ahí busca «LeePantallaCel» (puede estar dentro de «Apps instaladas» o «Servicios descargados») y actívalo.\n\nSi te sale «Ajuste restringido»: toca el botón de abajo, luego el menú ⋮ (arriba a la derecha) → «Permitir ajustes restringidos», y vuelve a intentarlo."))
        col.addView(boton("Abrir información de la app", false) {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            startActivity(i)
        })
        btnBurbuja = boton("Ocultar ícono flotante", false) {
            val s = LectorService.instancia
            if (s == null) {
                avisar("Primero activa el lector")
            } else {
                if (s.burbujaVisible()) s.ocultarBurbuja() else s.mostrarBurbuja()
                refrescarEstado()
            }
        }
        col.addView(btnBurbuja)

        // Paso 2
        col.addView(subtitulo("2. Escoger voz"))
        lblVoces = parrafo("Cargando voces…")
        col.addView(lblVoces)
        spinner = Spinner(this)
        col.addView(spinner)
        col.addView(boton("▶ Probar voz", true) { probarVoz() })
        col.addView(boton("Descargar más voces", false) {
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (e: Exception) {
                abrirAjustesVoz()
            }
        })
        col.addView(boton("Ajustes de voz del teléfono", false) { abrirAjustesVoz() })

        // Velocidad
        col.addView(subtitulo("3. Velocidad"))
        lblVel = parrafo("")
        col.addView(lblVel)
        val barra = SeekBar(this).apply {
            max = 20
            progress = Math.round((Ajustes.velocidad(this@MainActivity) - 0.5f) * 10)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, deUsuario: Boolean) {
                    val v = 0.5f + p / 10f
                    Ajustes.guardarVelocidad(this@MainActivity, v)
                    mostrarVelocidad()
                    LectorService.instancia?.aplicarVoz()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        col.addView(barra)
        mostrarVelocidad()

        // Opciones
        col.addView(subtitulo("4. Opciones"))
        val chk = CheckBox(this).apply {
            text = "Leer también los nombres de botones e imágenes (más completo, pero lee cosas como «Atrás» o «Menú»)"
            textSize = 15f
            isChecked = Ajustes.descripciones(this@MainActivity)
            setOnCheckedChangeListener { _, b -> Ajustes.guardarDescripciones(this@MainActivity, b) }
        }
        col.addView(chk)

        col.addView(parrafo("El texto se lee en tu teléfono; no se manda a ningún lado."))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FBF8F1"))
            addView(col)
        }
        setContentView(scroll)
    }

    private fun refrescarEstado() {
        val s = LectorService.instancia
        if (s != null) {
            lblEstado.text = "✅ El lector está activo."
            btnActivar.text = "Ajustes de Accesibilidad"
            btnBurbuja.visibility = View.VISIBLE
            btnBurbuja.text = if (s.burbujaVisible()) "Ocultar ícono flotante" else "Mostrar ícono flotante"
        } else {
            lblEstado.text = "⚠️ El lector está apagado. Actívalo para que aparezca el ícono flotante."
            btnActivar.text = "Abrir ajustes de Accesibilidad"
            btnBurbuja.visibility = View.GONE
        }
    }

    private fun mostrarVelocidad() {
        lblVel.text = "Velocidad: ${"%.1f".format(Ajustes.velocidad(this))}x  (1.0 = normal)"
    }

    // ------------------------------------------------------------------ voces

    private fun cargarVoces() {
        val t = tts ?: return
        val todas = try { t.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        voces = todas
            .filter { it.locale.language == "es" }
            .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy<Voice>({ ordenPais(it.locale.country) }, { it.isNetworkConnectionRequired }, { it.name }))

        if (voces.isEmpty()) {
            lblVoces.text = "No encontré voces en español. Toca «Descargar más voces»."
            return
        }
        lblVoces.text = "Tienes ${voces.size} voces en español. Escoge una y tócale «Probar voz»."
        val es = Locale("es")
        val etiquetas = voces.mapIndexed { i, v ->
            val pais = v.locale.getDisplayCountry(es).ifBlank { "Español" }
            val red = if (v.isNetworkConnectionRequired) "con internet" else "sin internet"
            "Voz ${i + 1} · $pais · $red"
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, etiquetas)
        val guardada = Ajustes.voz(this)
        val pos = voces.indexOfFirst { it.name == guardada }
        if (pos >= 0) spinner.setSelection(pos)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, posicion: Int, id: Long) {
                Ajustes.guardarVoz(this@MainActivity, voces[posicion].name)
                LectorService.instancia?.aplicarVoz()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun ordenPais(c: String): Int = when (c) {
        "MX" -> 0
        "US" -> 1
        "419" -> 2
        "ES" -> 4
        else -> 3
    }

    private fun probarVoz() {
        val t = tts ?: return
        val pos = spinner.selectedItemPosition
        if (pos in voces.indices) t.setVoice(voces[pos])
        t.setSpeechRate(Ajustes.velocidad(this))
        t.speak(
            "Hola. Esta es la voz con la que te voy a leer. Paz y bien.",
            TextToSpeech.QUEUE_FLUSH, null, "prueba"
        )
    }

    private fun abrirAjustesVoz() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            avisar("No pude abrir los ajustes de voz")
        }
    }

    // ------------------------------------------------------------------ piezas

    private fun titulo(s: String, tam: Float) = TextView(this).apply {
        text = s
        textSize = tam
        setTextColor(oscuro)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun subtitulo(s: String) = TextView(this).apply {
        text = s
        textSize = 20f
        setTextColor(Color.parseColor("#8A6D0F"))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun parrafo(s: String) = TextView(this).apply {
        text = s
        textSize = 16f
        setTextColor(Color.parseColor("#3A352C"))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun boton(s: String, principal: Boolean, accion: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 16f
        setTextColor(if (principal) oscuro else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (principal) dorado else Color.parseColor("#3A352C"))
        }
        minHeight = dp(52)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(6), 0, dp(6)) }
        setOnClickListener { accion() }
    }

    private fun avisar(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}

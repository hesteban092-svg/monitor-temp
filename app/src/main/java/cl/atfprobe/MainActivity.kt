package cl.atfprobe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * Monitor en vivo (solo lectura): refrigerante (0105) e IAT (010F) estándar de la TCM,
 * más los identificadores UDS candidatos, todos como dato-40 en °C.
 * Sirve para ver cuál identificador es el ATF comparándolo con refrigerante e IAT.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private val elm = Elm()
    private var devices: List<BluetoothDevice> = emptyList()

    private lateinit var spinner: Spinner
    private lateinit var header: EditText
    private lateinit var didsEt: EditText
    private lateinit var out: TextView

    @Volatile private var stop = false
    @Volatile private var busy = false
    private val csv = StringBuilder()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        spinner = Spinner(this)
        root.addView(spinner)

        header = EditText(this).apply { setText("7E1"); setSingleLine() }
        root.addView(label("Cabecera del módulo (TCM = 7E1):"))
        root.addView(header)

        didsEt = EditText(this).apply { setText("1286,1287,1288"); setSingleLine() }
        root.addView(label("Identificadores a monitorear (hex, coma):"))
        root.addView(didsEt)

        root.addView(row(
            btn("Conectar") { connect() },
            btn("Iniciar") { start() },
            btn("Detener") { stop = true }
        ))

        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 18f
            setPadding(0, dp(12), 0, dp(12))
            text = "1) Conectar  2) Iniciar\nCompara cada identificador con Refrigerante e Aire de admisión: el que sube parecido a esos no es el ATF; el que sube más lento y distinto sí."
        }
        root.addView(out, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(btn("Compartir registro (CSV)") { share() })
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        } else {
            loadDevices()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        loadDevices()
    }

    override fun onDestroy() {
        stop = true
        elm.close()
        super.onDestroy()
    }

    private fun loadDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        devices = adapter?.bondedDevices?.toList() ?: emptyList()
        val names = devices.map { "${it.name} (${it.address})" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val i = devices.indexOfFirst { (it.name ?: "").contains("OBD", ignoreCase = true) }
        if (i >= 0) spinner.setSelection(i)
    }

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 12f }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    private fun show(s: String) = runOnUiThread { out.text = s }

    private fun work(block: () -> Unit) {
        if (busy) { show("Ocupado. Pulsa Detener y espera un segundo."); return }
        busy = true
        stop = false
        Thread {
            try { block() } catch (e: Exception) { show("ERROR: ${e.message}") } finally { busy = false }
        }.start()
    }

    private fun connect() = work {
        val d = devices.getOrNull(spinner.selectedItemPosition)
        if (d == null) { show("Sin dispositivo seleccionado."); return@work }
        show("Conectando a ${d.name}...")
        elm.close()
        elm.connect(d)
        elm.init(header.text.toString().trim().uppercase())
        show("Conectado. Pulsa Iniciar.")
    }

    private fun readFirstByte(req: String, tag: String): Int? {
        val hex = Elm.parse(elm.send(req, 600))
        return if (hex != null && hex.startsWith(tag) && hex.length >= tag.length + 2)
            hex.substring(tag.length, tag.length + 2).toInt(16) else null
    }

    private fun fmt(v: Int?) = if (v == null) "--" else "%4d °C  (raw %d)".format(v - 40, v)

    private fun start() = work {
        val dids = didsEt.text.toString().split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        csv.setLength(0)
        csv.append("t_s,refrigerante,aire_adm,${dids.joinToString(",")}\n")
        val t0 = System.currentTimeMillis()
        while (!stop) {
            val cool = readFirstByte("0105", "4105")
            val iat = readFirstByte("010F", "410F")
            val vals = dids.map { readFirstByte("22$it", "62$it") }
            val sb = StringBuilder()
            sb.append("Refrigerante  ${fmt(cool)}\n")
            sb.append("Aire admisión ${fmt(iat)}\n\n")
            dids.forEachIndexed { i, d -> sb.append("DID $d     ${fmt(vals[i])}\n") }
            show(sb.toString())
            if (csv.length < 300000) {
                val t = (System.currentTimeMillis() - t0) / 1000.0
                csv.append("%.1f,%s,%s,%s\n".format(
                    t,
                    cool?.let { it - 40 } ?: "",
                    iat?.let { it - 40 } ?: "",
                    vals.joinToString(",") { v -> v?.let { it - 40 }?.toString() ?: "" }
                ))
            }
        }
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, csv.toString())
        }
        startActivity(Intent.createChooser(i, "Compartir registro"))
    }
}

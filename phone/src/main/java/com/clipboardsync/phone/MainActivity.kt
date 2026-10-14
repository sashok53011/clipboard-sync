package com.clipboardsync.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.clipboardsync.core.Prefs
import com.clipboardsync.core.SyncEngine

class MainActivity : Activity(), SyncEngine.Listener {

    private lateinit var swSync: Switch
    private lateinit var txtStatus: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var txtLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var editIp: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Движок должен быть инициализирован до первого переключения.
        SyncEngine.init(this, "phone")

        swSync = findViewById(R.id.swSync)
        txtStatus = findViewById(R.id.txtStatus)
        peerList = findViewById(R.id.peerList)
        txtLog = findViewById(R.id.txtLog)
        logScroll = findViewById(R.id.logScroll)
        editIp = findViewById(R.id.editIp)

        swSync.isChecked = Prefs.enabled(this)
        swSync.setOnCheckedChangeListener { _, checked ->
            setSync(checked)
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.toast_find_acc, Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnAddIp).setOnClickListener {
            val ip = editIp.text.toString().trim()
            if (ip.isNotEmpty()) {
                SyncEngine.addManualTarget(ip)
                editIp.text.clear()
                refresh()
            }
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val long = buildString {
                appendLine("Тест Clipboard Sync $t")
                appendLine()
                appendLine("Это длинное сообщение для проверки прокрутки на часах.")
                appendLine()
                repeat(6) { i ->
                    appendLine("Абзац ${i + 1}. Съ ещё же ёж ёлка ёжик — шуршит листва под ногами, и ветер тихо поёт свою песню над крышами домов. Копипаста длинная, чтобы текста хватило на несколько экранов вниз.")
                    appendLine()
                }
                appendLine("Конец сообщения. Свайп вниз — закрыть.")
            }
            SyncEngine.sendTest(long)
            Toast.makeText(this, R.string.toast_test_sent, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            requestBatteryExemption()
        }

        requestNeededPermissions()

        if (Prefs.enabled(this)) {
            PhoneSyncService.start(this)
        }
    }

    override fun onStart() {
        super.onStart()
        SyncEngine.addListener(this)
        handler.post(refreshRunnable)
    }

    override fun onStop() {
        super.onStop()
        SyncEngine.removeListener(this)
        handler.removeCallbacks(refreshRunnable)
    }

    // ------------------------------------------------------------------ engine

    private fun setSync(enabled: Boolean) {
        if (enabled) {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, R.string.toast_enable_acc, Toast.LENGTH_LONG).show()
            }
            SyncEngine.setEnabled(true)
            PhoneSyncService.start(this)
        } else {
            SyncEngine.setEnabled(false)
            stopService(Intent(this, PhoneSyncService::class.java))
        }
        refresh()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java)
        return am?.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )?.any { it.id.contains(packageName) } == true
    }

    private fun refresh() {
        txtStatus.text = SyncEngine.statusText(this)

        peerList.removeAllViews()
        val peers = SyncEngine.peers()
        if (peers.isEmpty()) {
            val tv = TextView(this)
            tv.text = getString(R.string.devices_empty)
            tv.setPadding(0, 8, 0, 8)
            peerList.addView(tv)
        } else {
            for (p in peers) {
                val tv = TextView(this)
                val mark = if (p.connected) "●" else "○"
                tv.text = "$mark  ${p.name}\n     ${p.platform} · ${p.transport} · ${p.address}"
                tv.setPadding(0, 8, 0, 8)
                peerList.addView(tv)
            }
        }

        txtLog.text = SyncEngine.events().joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_UP) }
    }

    // ------------------------------------------------------------------ perms

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1)
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val i = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(i)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.toast_battery_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, R.string.toast_battery_ok, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ listener

    override fun onStateChanged() {
        runOnUiThread { refresh() }
    }

    override fun onPeersChanged() {
        runOnUiThread { refresh() }
    }

    override fun onEvent(text: String) {
        runOnUiThread { refresh() }
    }
}

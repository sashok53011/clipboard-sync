package com.clipboardsync.wear

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
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
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
    private lateinit var scroll: ScrollView

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
        SyncEngine.init(this, "wear")

        swSync = findViewById(R.id.swSync)
        txtStatus = findViewById(R.id.txtStatus)
        peerList = findViewById(R.id.peerList)
        txtLog = findViewById(R.id.txtLog)
        scroll = findViewById(R.id.scroll)

        swSync.isChecked = Prefs.enabled(this)
        swSync.setOnCheckedChangeListener { _, checked -> setSync(checked) }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.toast_find_acc, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnAddIp).setOnClickListener {
            val edit = findViewById<EditText>(R.id.editIp)
            val ip = edit.text.toString().trim()
            if (ip.isNotEmpty()) {
                SyncEngine.addManualTarget(ip)
                edit.text.clear()
                refresh()
            }
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            SyncEngine.sendTest("Тест Clipboard Sync (часы) $t")
            Toast.makeText(this, R.string.toast_test_sent, Toast.LENGTH_SHORT).show()
        }

        requestNeededPermissions()

        if (Prefs.enabled(this)) {
            WearSyncService.start(this)
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

    /** Прокрутка вращением безеля/коронки (ротовые события Wear OS). */
    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && event.source == InputDevice.SOURCE_ROTARY_ENCODER) {
            val delta = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (delta != 0f) {
                scroll.scrollBy(0, (-delta * 50).toInt())
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun setSync(enabled: Boolean) {
        if (enabled) {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, R.string.toast_enable_acc, Toast.LENGTH_LONG).show()
            }
            SyncEngine.setEnabled(true)
            WearSyncService.start(this)
        } else {
            SyncEngine.setEnabled(false)
            stopService(Intent(this, WearSyncService::class.java))
        }
        refresh()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
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
            tv.setPadding(0, 4, 0, 4)
            tv.textSize = 12f
            peerList.addView(tv)
        } else {
            for (p in peers) {
                val tv = TextView(this)
                val mark = if (p.connected) "●" else "○"
                tv.text = "$mark ${p.name} (${p.transport})"
                tv.setPadding(0, 4, 0, 4)
                tv.textSize = 12f
                peerList.addView(tv)
            }
        }

        val events = SyncEngine.events().take(5)
        txtLog.text = events.joinToString("\n")
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1)
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

package com.clipboardsync.wear

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.wear.widget.SwipeDismissFrameLayout

/**
 * Экран показа принятого текста:
 *  - показывается поверх блокировки и включает экран, если он был выключен;
 *  - пока открыт, экран не гаснет (FLAG_KEEP_SCREEN_ON);
 *  - длинный текст листается;
 *  - закрывается свайпом вниз или по кнопке «Назад»;
 *  - автоматически закрывается через 2 минуты, чтобы не разряжать батарею.
 */
class ReceivedTextActivity : Activity() {

    private lateinit var swipe: SwipeDismissFrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var txtFrom: TextView
    private lateinit var txtReceived: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Показ поверх блокировки + включение экрана.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Экран не гаснет, пока показано сообщение.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_received)

        swipe = findViewById(R.id.swipeDismiss)
        scroll = findViewById(R.id.scroll)
        txtFrom = findViewById(R.id.txtFrom)
        txtReceived = findViewById(R.id.txtReceived)

        swipe.addCallback(object : SwipeDismissFrameLayout.Callback() {
            override fun onDismissed(container: SwipeDismissFrameLayout) {
                finish()
            }
        })

        show(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
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

    private fun show(intent: android.content.Intent?) {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: return
        val from = intent.getStringExtra(EXTRA_FROM) ?: ""
        txtFrom.text = getString(R.string.received_from, from)
        txtReceived.text = text
        // Сбрасываем автозакрытие при каждом новом сообщении.
        handler.removeCallbacks(autoClose)
        handler.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoClose)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_FROM = "from"
        private const val AUTO_CLOSE_MS = 120_000L
    }
}

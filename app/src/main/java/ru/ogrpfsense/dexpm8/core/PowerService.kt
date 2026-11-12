package ru.ogrpfsense.dexpm8.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Служба спецвозможностей только для «Выключения» на шторке B5 (п. 8.1): обычному приложению
 * выключать книгу нельзя, а служба может открыть системное меню питания
 * (`GLOBAL_ACTION_POWER_DIALOG`, на книге работает — 2026-11-12). События не читает, окна не смотрит.
 * Экрана «Спец. возможности» в прошивке книги нет: службу включают один раз через adb
 * (`settings put secure enabled_accessibility_services …`, docs/BUILD.md).
 */
class PowerService : AccessibilityService() {

    companion object {
        /** Подключённая служба; null — выключена в «Спец. возможностях». */
        @Volatile
        var instance: PowerService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Trace.step("power: service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

package com.g7monitor.shared.ui

import com.g7monitor.shared.platform.currentTimeMillis
import com.g7monitor.shared.platform.fireNotification

/** Prüft jeden neuen Glukosewert gegen die Hypo-/Hyper-Schwellen und löst
 *  (gedrosselt per Wiederhol-Intervall) eine Benachrichtigung aus. */
object Alarms {
    private var lastHypo = 0L
    private var lastHyper = 0L

    fun check(mgdl: Int) {
        val now = currentTimeMillis()
        val repeatMs = AppState.alarmRepeatMin * 60_000L
        if (AppState.hypoEnabled && mgdl < AppState.hypoThreshold && now - lastHypo >= repeatMs) {
            lastHypo = now
            fireNotification("Unterzucker-Alarm", "$mgdl mg/dL — unter ${AppState.hypoThreshold} mg/dL", AppState.alarmSound)
        }
        if (AppState.hyperEnabled && mgdl > AppState.hyperThreshold && now - lastHyper >= repeatMs) {
            lastHyper = now
            fireNotification("Überzucker-Alarm", "$mgdl mg/dL — über ${AppState.hyperThreshold} mg/dL", AppState.alarmSound)
        }
    }

    /** Echter Selbsttest (löst direkt eine Hypo-Benachrichtigung aus). */
    fun test() = fireNotification("Test-Alarm", "Selbsttest des Unterzucker-Alarms.", AppState.alarmSound)
}

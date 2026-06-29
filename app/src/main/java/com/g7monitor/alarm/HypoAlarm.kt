/*
 * Hypo-/Hyper-Alarm via High-Priority-Notification.
 *
 * Eigener Notification-Channel (IMPORTANCE_HIGH), damit der Alarm auch
 * bei aktiver CGM-Foreground-Notification UND bei gesperrtem Screen
 * sichtbar und hörbar ist.
 *
 * WICHTIG zum Ton (häufiger Android-Stolperstein):
 *  - Ab Android O steuert NUR der Channel Sound + Vibration. Ein einmal
 *    angelegter Channel lässt sich per Code nicht mehr ändern — wurde er
 *    mal ohne (funktionierenden) Ton erstellt, bleibt er stumm.
 *  - Deshalb hängt die Channel-ID hier an Sound-/Vibrations-Wunsch UND an
 *    einer Versionsnummer. Ändert der User den Ton-Schalter, wird ein
 *    anderer, frisch konfigurierter Channel benutzt. Erhöht man CHANNEL_VER,
 *    entstehen ebenfalls frische Channels (z. B. nach einem Bugfix).
 *  - Der Default-Alarmton-URI ist auf manchen Geräten leer; darum eine
 *    Fallback-Kette Alarm → Notification → Ringtone.
 *
 * minSdk ist 26 (Android O) — Pre-O-Sonderbehandlung entfällt komplett.
 */
package com.g7monitor.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.g7monitor.MainActivity
import com.g7monitor.util.DebugLog
import com.g7monitor.vm.SettingsState

object HypoAlarm {

    private const val TAG = "HypoAlarm"
    private const val NOTIF_ID = 0x6738

    /** Channel-Generation. Bei Bedarf hochzählen, dann legt die App beim
     *  nächsten Alarm garantiert frische Channels mit korrekter Konfig an. */
    private const val CHANNEL_VER = 4

    /** Alte Channel-IDs, die aufgeräumt werden (kaputte/veraltete Sound-Settings). */
    private val LEGACY_CHANNELS = listOf(
        "g7_hypo_alarm",
        "g7_alarm_v2_sv", "g7_alarm_v2_sx", "g7_alarm_v2_xv", "g7_alarm_v2_xx",
        "g7_alarm_v3_sv", "g7_alarm_v3_sx", "g7_alarm_v3_xv", "g7_alarm_v3_xx",
    )

    enum class Kind { Hypo, Hyper }

    fun trigger(ctx: Context, kind: Kind, mgdl: Int, settings: SettingsState) {
        val channelId = ensureChannel(ctx)

        val title = when (kind) {
            Kind.Hypo  -> "Unterzucker: $mgdl mg/dL"
            Kind.Hyper -> "Überzucker: $mgdl mg/dL"
        }
        val text = when (kind) {
            Kind.Hypo  -> "Unter ${settings.hypoThreshold} mg/dL — prüfen!"
            Kind.Hyper -> "Über ${settings.hyperThreshold} mg/dL — prüfen!"
        }

        val openApp = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val contentIntent = PendingIntent.getActivity(ctx, 0, openApp, pendingFlags)

        val builder = NotificationCompat.Builder(ctx, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, builder.build())

        // Zusätzlich direkt abspielen — unabhängig von Channel-Caching, „Nicht
        // stören" und Notification-Eigenheiten. Läuft über den Alarm-Stream.
        if (settings.alarmSound) playSoundDirect(ctx)
        if (settings.alarmVibrate) vibrateDirect(ctx)
    }

    /**
     * Stellt sicher, dass der zu Sound/Vibration passende Channel existiert,
     * und gibt dessen ID zurück. Erstellt ihn nur, falls noch nicht da.
     */
    private fun ensureChannel(ctx: Context): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Veraltete Channels wegräumen (alte/teils defekte Sound-Konfig).
        for (old in LEGACY_CHANNELS) {
            try { nm.deleteNotificationChannel(old) } catch (_: Throwable) {}
        }

        val id = channelId()
        if (nm.getNotificationChannel(id) != null) return id

        val ch = NotificationChannel(
            id,
            "Pebbels Alarm",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Warnung bei Unter- oder Überzucker"
            setBypassDnd(false)
            setShowBadge(true)
            // Ton UND Vibration kommen direkt aus dem Code (MediaPlayer +
            // Vibrator), nicht vom Channel — das umgeht Androids Notification-
            // Throttling und das Channel-Sound-Caching. Channel daher stumm.
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
        return id
    }

    /** Channel-ID — nur an die Channel-Generation gebunden. Ton/Vibration
     *  laufen direkt über Code, daher braucht der Channel keine Sound-Varianten.
     *  Ein Bugfix lässt sich über CHANNEL_VER ausrollen (frischer Channel). */
    private fun channelId(): String = "g7_alarm_v$CHANNEL_VER"

    /** Sound-URI mit Fallback. Auf manchen Geräten/ROMs ist der Default-
     *  Alarmton nicht gesetzt — dann Notification- bzw. Ringtone-Default. */
    private fun alarmSoundUri(): Uri? {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    @Volatile private var player: android.media.MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    /** Stoppt den direkten Alarmton ZUVERLÄSSIG — manuell („Stopp"-Knopf), beim
     *  Öffnen der App oder per 5-Sekunden-Timeout. Gibt den MediaPlayer komplett
     *  frei, damit der nächste Test garantiert wieder sauber startet. */
    fun stop() {
        mainHandler.removeCallbacks(autoStop)
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        player = null
        DebugLog.i(TAG, "Alarmton gestoppt")
    }

    /** Spielt den Alarmton DIREKT über einen MediaPlayer auf dem Alarm-Stream.
     *  Bewusst MediaPlayer statt Ringtone: Ringtone ließ sich auf vielen Geräten
     *  nicht zuverlässig stoppen und kein zweites Mal starten. Läuft in Schleife
     *  und stoppt automatisch nach max. 5 s. */
    private fun playSoundDirect(ctx: Context) {
        try {
            val uri = alarmSoundUri() ?: run {
                DebugLog.w(TAG, "Kein Alarmton-URI fürs direkte Abspielen")
                return
            }
            stop() // evtl. laufenden Player erst sauber freigeben
            val mp = android.media.MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(ctx, uri)
            mp.isLooping = true
            mp.setOnErrorListener { _, what, extra ->
                DebugLog.w(TAG, "MediaPlayer-Fehler $what/$extra"); stop(); true
            }
            mp.setOnPreparedListener { runCatching { it.start() } }
            mp.prepareAsync()
            player = mp
            mainHandler.postDelayed(autoStop, 5_000L)
            DebugLog.i(TAG, "Direkter Alarmton gestartet (MediaPlayer, max 5 s)")
        } catch (t: Throwable) {
            DebugLog.w(TAG, "Direkter Alarmton fehlgeschlagen: ${t.message}")
            stop()
        }
    }

    /** Vibriert direkt — ergänzend zur Channel-Vibration. */
    private fun vibrateDirect(ctx: Context) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            val pattern = longArrayOf(0, 400, 250, 400)
            val effect = android.os.VibrationEffect.createWaveform(pattern, -1)
            // Mit USAGE_ALARM, damit die Vibration auch bei „Nicht stören" /
            // Lautlos durchkommt (wie ein echter Wecker).
            if (Build.VERSION.SDK_INT >= 33) {
                val va = android.os.VibrationAttributes.Builder()
                    .setUsage(android.os.VibrationAttributes.USAGE_ALARM).build()
                vib.vibrate(effect, va)
            } else {
                @Suppress("DEPRECATION")
                val aa = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                @Suppress("DEPRECATION")
                vib.vibrate(effect, aa)
            }
        } catch (t: Throwable) {
            DebugLog.w(TAG, "Direkte Vibration fehlgeschlagen: ${t.message}")
        }
    }
}

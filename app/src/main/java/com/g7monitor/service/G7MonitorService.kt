/*
 * Foreground-Service, der den Prozess am Leben hält, solange die App mit dem
 * G7 verbunden ist. Ohne diesen Service killt Android den Prozess, sobald die
 * Activity weggewischt wird — die BLE-Verbindung fliegt raus und die App
 * liefert keine Werte mehr, bis der User sie aktiv wieder öffnet.
 *
 * Aufgaben:
 *  - Foreground-Notification anzeigen (sonst beendet Android den Service nach ~5 s)
 *  - Notification mit dem jeweils letzten Glukosewert aktualisieren
 *  - Nichts selbst starten — der BLE-Client wird vom G7Repository verwaltet.
 *    Dieser Service ist reiner Prozess-Halter.
 */
package com.g7monitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.g7monitor.MainActivity
import com.g7monitor.vm.ConnectionState
import com.g7monitor.vm.G7Repository
import com.g7monitor.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class G7MonitorService : Service() {

    private val tag = "G7MonitorService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var uploadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startInForeground(buildNotification(text = "Warte auf Verbindung ..."))
        acquireWakeLock()
        // Falls der Service nach einem System-Kill ohne Activity wieder
        // hochkommt (START_STICKY), ist der Prozess frisch und G7Repository
        // leer. init() lädt Readings + PIN aus dem Store und startet bei
        // vorhandenem Sensor-Snapshot automatisch einen Scan.
        G7Repository.init(applicationContext)
        // Auf Änderungen in lastGlucose/connection reagieren und die
        // Notification entsprechend aktualisieren. distinctUntilChanged spart
        // Notification-Updates bei identischem Inhalt.
        collectJob = scope.launch {
            G7Repository.state
                .map { s -> renderNotifContent(s.lastGlucose, s.lastGlucoseAt, s.connection, s.statusMessage) }
                .distinctUntilChanged()
                .collect { content ->
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIF_ID, buildNotification(text = content))
                }
        }
        // Cloud-Upload-Schleife: schickt alle 5 Minuten die letzten 6 h an das
        // Pebbels-Dashboard. performCloudUpload() prüft selbst, ob der Upload
        // in den Einstellungen aktiviert ist — die Schleife läuft immer, tut
        // aber nichts, solange der User den Schalter aus hat. Der HTTP-Call
        // läuft auf Dispatchers.IO, damit der Main-Thread frei bleibt.
        uploadJob = scope.launch {
            // Erst kurz warten — beim Service-Start ist die BLE-Verbindung
            // noch nicht zwingend aufgebaut, und die History wird gerade
            // erst geladen. 60 s reichen, damit der erste Upload Sinn ergibt.
            delay(60_000L)
            while (isActive) {
                try {
                    // withTimeoutOrNull härtet gegen hängende HTTP-Calls:
                    // Falls die Verbindung trotz der 15-s-Timeouts im
                    // CloudUploader in einer Phase festhängt, die
                    // HttpURLConnection nicht sauber abdeckt (DNS-Auflösung,
                    // TLS-Handshake), bricht die Coroutine nach 90 s ab und
                    // die Schleife läuft weiter. Ohne diese Absicherung kann
                    // ein einziger hängender Upload den kompletten
                    // 5-Minuten-Takt dauerhaft blockieren — dann hilft nur
                    // ein App-Neustart. Genau das soll nicht mehr passieren.
                    val ok = withTimeoutOrNull(90_000L) {
                        withContext(Dispatchers.IO) {
                            G7Repository.performCloudUpload()
                        }
                    }
                    if (ok == null) {
                        DebugLog.w(tag, "Cloud-Upload: Timeout nach 90 s — übersprungen, nächster Versuch in 5 Min")
                    }
                } catch (t: Throwable) {
                    DebugLog.w(tag, "Cloud-Upload-Tick fehlgeschlagen", t)
                }
                delay(UPLOAD_INTERVAL_MS)
            }
        }
        Log.i(tag, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(tag, "onStartCommand flags=$flags startId=$startId")
        // START_STICKY: wenn Android den Service killt (z. B. Speicherdruck),
        // wird er automatisch neu gestartet. onStartCommand kommt dann mit
        // einem null-Intent zurück — wir laufen einfach weiter und warten auf
        // State-Updates vom Repository.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(tag, "onDestroy")
        collectJob?.cancel()
        uploadJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Partial-Wakelock: hält die CPU wach, solange der Service läuft.
     *
     * Warum nötig: Geht der Bildschirm aus, schickt Android das Gerät nach
     * kurzer Zeit in den Doze-Modus. Dort werden Coroutine-Timer (delay) und
     * Netzwerkzugriffe ausgesetzt — der 5-Minuten-Upload-Takt würde stehen
     * bleiben und auch die BLE-Verarbeitung gedrosselt. Ein Partial-Wakelock
     * hält nur die CPU wach (NICHT den Bildschirm), damit der Service
     * weiterarbeitet. Für eine durchgehende CGM-Überwachung ist der moderate
     * Mehrverbrauch vertretbar — etablierte CGM-Apps machen das ebenso.
     */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pebbels:monitor")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
            Log.i(tag, "Wakelock gehalten")
        } catch (t: Throwable) {
            Log.w(tag, "Wakelock konnte nicht geholt werden", t)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {}
        wakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User hat die App aus Recents gewischt — Service läuft weiter,
        // damit die BLE-Verbindung nicht abreißt. Das ist der ganze Zweck
        // dieses Service.
        Log.i(tag, "onTaskRemoved — Service bleibt am Leben")
        super.onTaskRemoved(rootIntent)
    }

    // ---------------------------------------------------------------------

    private fun startInForeground(notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: foregroundServiceType MUSS zum Manifest passen.
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Pebbels APP",
                    NotificationManager.IMPORTANCE_LOW       // leise, bleibt aber sichtbar
                ).apply {
                    description = "Hält die Verbindung zum G7 aufrecht"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val contentIntent = PendingIntent.getActivity(this, 0, openApp, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pebbels APP")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)                                   // nicht wegwischbar
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun renderNotifContent(
        mgdl: Int?,
        at: Long?,
        conn: ConnectionState,
        status: String,
    ): String {
        if (mgdl != null && at != null) {
            val ageMin = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0)
            return if (ageMin <= 1) "$mgdl mg/dL — jetzt"
                   else "$mgdl mg/dL — $ageMin min"
        }
        return when (conn) {
            ConnectionState.Scanning -> "Suche Sensor ..."
            ConnectionState.Connecting -> "Verbinde ..."
            ConnectionState.Bonded, ConnectionState.Authenticating -> "Handshake ..."
            ConnectionState.Authenticated -> "Authentifiziert"
            ConnectionState.Receiving -> "Empfange ..."
            ConnectionState.Error -> "Fehler — siehe App"
            ConnectionState.Idle -> "Bereit"
            ConnectionState.Found -> "Sensor gefunden"
        }
    }

    companion object {
        private const val CHANNEL_ID = "g7_monitor_channel"
        private const val NOTIF_ID = 0x6737                 // beliebig, sonst ungenutzt
        /** Abstand zwischen zwei Cloud-Uploads: 5 Minuten — passt zum
         *  5-Minuten-Takt, in dem der Sensor neue Werte liefert. */
        private const val UPLOAD_INTERVAL_MS = 5L * 60L * 1000L
    }
}

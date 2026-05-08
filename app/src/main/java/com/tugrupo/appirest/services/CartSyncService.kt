package com.tugrupo.appirest.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface SyncEvent {
    object Idle    : SyncEvent
    object Sending : SyncEvent
    data class Success(val orderId: Int)   : SyncEvent
    data class Failure(val reason: String) : SyncEvent
}

class CartSyncService : Service() {

    companion object {
        const val CHANNEL_ID       = "cart_sync_channel"
        const val NOTIFICATION_ID  = 101
        const val EXTRA_ITEM_COUNT = "extra_item_count"
        const val EXTRA_TOTAL      = "extra_total"

        val syncEvent = MutableStateFlow<SyncEvent>(SyncEvent.Idle)
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val itemCount = intent?.getIntExtra(EXTRA_ITEM_COUNT, 0) ?: 0
        val total     = intent?.getDoubleExtra(EXTRA_TOTAL, 0.0) ?: 0.0

        // El servicio arranca y muestra la notificación obligatoria (no quitável)
        startForeground(NOTIFICATION_ID, buildSendingNotification(itemCount, total))

        serviceScope.launch {
            syncEvent.collect { event ->
                when (event) {
                    is SyncEvent.Idle    -> { /* nada */ }

                    is SyncEvent.Sending ->
                        // Refresca la notificación de proceso — sigue siendo ongoing
                        updateForeground(buildSendingNotification(itemCount, total))

                    is SyncEvent.Success -> {
                        // CLAVE: primero detiene el foreground SIN quitar la notificación
                        // (STOP_FOREGROUND_DETACH), luego la reemplaza con la de éxito.
                        // Así el servicio muere pero la notificación queda visible
                        // y ahora SÍ se puede descartar (ongoing = false).
                        stopForeground(STOP_FOREGROUND_DETACH)
                        notify(buildSuccessNotification(event.orderId))
                        syncEvent.value = SyncEvent.Idle
                        stopSelf()
                    }

                    is SyncEvent.Failure -> {
                        stopForeground(STOP_FOREGROUND_DETACH)
                        notify(buildErrorNotification(event.reason))
                        syncEvent.value = SyncEvent.Idle
                        stopSelf()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    // Actualiza la notificación del foreground en curso
    private fun updateForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // En Android 10+ se puede actualizar directamente
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ── Notificación de proceso — NO se puede quitar (ongoing = true) ─────────
    private fun buildSendingNotification(itemCount: Int, total: Double): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛒 Procesando tu pedido…")
            .setContentText("Enviando $itemCount producto(s) · $${"%.2f".format(total)}")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "Estamos enviando tu pedido de $itemCount producto(s) al servidor.\n" +
                                "Total: $${"%.2f".format(total)}\n\n" +
                                "⚠️ No puedes quitar esta notificación hasta que termine."
                    )
            )
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(0, 0, true)          // barra animada infinita
            .setOngoing(true)                 // ← NO se puede deslizar ni quitar
            .setOnlyAlertOnce(true)
            .setColor(0xFF6200EE.toInt())
            .setColorized(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    // ── Notificación de éxito — SÍ se puede quitar (ongoing = false) ─────────
    private fun buildSuccessNotification(orderId: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("✅ ¡Pedido confirmado!")
            .setContentText("Tu pedido #$orderId fue creado exitosamente.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Tu pedido #$orderId fue registrado correctamente. ¡Gracias por tu compra!")
            )
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setColor(0xFF4CAF50.toInt())
            .setColorized(true)
            .setOngoing(false)                // ← ahora SÍ se puede deslizar
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    // ── Notificación de error — SÍ se puede quitar ───────────────────────────
    private fun buildErrorNotification(reason: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("❌ Error al enviar pedido")
            .setContentText(reason)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("No pudimos procesar tu pedido.\nMotivo: $reason\n\nVuelve a intentarlo desde la app.")
            )
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setColor(0xFFF44336.toInt())
            .setColorized(true)
            .setOngoing(false)                // ← SÍ se puede deslizar
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pedidos en curso",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description      = "Muestra el estado de tu pedido en tiempo real"
                enableLights(true)
                lightColor       = Color.GREEN
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
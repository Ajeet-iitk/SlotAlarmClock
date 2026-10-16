package com.ajeet.slotalarmclock.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ajeet.slotalarmclock.R
import com.ajeet.slotalarmclock.RingActivity
import com.ajeet.slotalarmclock.data.AlarmStore

/**
 * Owns the ringing state: sound, vibration and the full-screen notification.
 *
 * A foreground service rather than a plain activity, because Android 10+ blocks background
 * activity starts. The full-screen intent on the notification is the supported way to reach
 * the lock screen, and it works precisely because the user configured a real alarm.
 */
class AlarmRingingService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopEverything() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                dismiss()
                return START_NOT_STICKY
            }

            ACTION_SNOOZE -> {
                snooze()
                return START_NOT_STICKY
            }
        }

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        startRinging()

        // Safety net: never ring forever if the user is away from the phone.
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, AUTO_STOP_MILLIS)
        return START_STICKY
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------- ringing

    private fun startRinging() {
        if (player == null) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            if (uri != null) {
                player = try {
                    MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIC)
                                .build(),
                        )
                        setWakeMode(this@AlarmRingingService, PowerManager.PARTIAL_WAKE_LOCK)
                        setDataSource(this@AlarmRingingService, uri)
                        isLooping = true
                        prepare()
                        start()
                    }
                } catch (_: Throwable) {
                    // A missing or unreadable ringtone still leaves vibration and the UI.
                    null
                }
            }
        }

        if (vibrator == null) {
            vibrator = getSystemService(Vibrator::class.java)?.also { device ->
                val pattern = longArrayOf(0L, 800L, 600L)
                device.vibrate(VibrationEffect.createWaveform(pattern, 0))
            }
        }
    }

    private fun release() {
        handler.removeCallbacks(autoStop)
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        vibrator?.let { runCatching { it.cancel() } }
        vibrator = null
    }

    private fun stopEverything() {
        release()
        // Let any open ringing screen close itself.
        sendBroadcast(Intent(ACTION_RINGING_STOPPED).setPackage(packageName))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun dismiss() {
        stopEverything()
        // The alarm is daily, so re-arm for tomorrow if it is still switched on.
        if (AlarmStore.get(this).state.value.enabled) AlarmScheduler.apply(this)
    }

    private fun snooze() {
        stopEverything()
        AlarmScheduler.scheduleAt(this, System.currentTimeMillis() + SNOOZE_MILLIS)
    }

    // -------------------------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_ringing),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.notif_channel_ringing_desc)
            // Sound and vibration are driven by this service so they can loop; silencing the
            // channel stops the platform from layering a second, one-shot alert on top.
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val state = AlarmStore.get(this).state.value
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val ringing = PendingIntent.getActivity(
            this,
            0,
            Intent(this, RingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            immutable,
        )
        val dismiss = PendingIntent.getService(
            this,
            1,
            Intent(this, AlarmRingingService::class.java).setAction(ACTION_DISMISS),
            immutable,
        )
        val snooze = PendingIntent.getService(
            this,
            2,
            Intent(this, AlarmRingingService::class.java).setAction(ACTION_SNOOZE),
            immutable,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(getString(R.string.notif_ringing_title))
            .setContentText(state.label)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(ringing)
            .setFullScreenIntent(ringing, true)
            .addAction(0, getString(R.string.action_snooze), snooze)
            .addAction(0, getString(R.string.action_dismiss), dismiss)
            .build()
    }

    companion object {
        const val ACTION_START = "com.ajeet.slotalarmclock.action.RING"
        const val ACTION_DISMISS = "com.ajeet.slotalarmclock.action.DISMISS"
        const val ACTION_SNOOZE = "com.ajeet.slotalarmclock.action.SNOOZE"

        /** Broadcast when ringing ends for any reason, so an open screen can dismiss itself. */
        const val ACTION_RINGING_STOPPED = "com.ajeet.slotalarmclock.action.RINGING_STOPPED"

        private const val CHANNEL_ID = "slot_alarm_ringing"
        private const val NOTIFICATION_ID = 0x51A1
        private const val AUTO_STOP_MILLIS = 10 * 60 * 1000L
        private const val SNOOZE_MILLIS = 5 * 60 * 1000L
    }
}

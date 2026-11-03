package com.phonemic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Notification
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.FileDescriptor
import java.io.PrintWriter

class ModelKeepAliveService : Service() {
    private lateinit var notification: Notification
    private lateinit var notificationBuilder: NotificationCompat.Builder
    override fun onCreate() {
        super.onCreate()
        instance = this
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel("phonemic.ready", "后台待机", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notificationBuilder = NotificationCompat.Builder(this, channel.id)
            .setSmallIcon(R.drawable.phonemic_foreground)
            .setContentTitle("PhoneMIC")
            .setContentText("语音键盘待机中")
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        notification = notificationBuilder.build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(41, notification, 0)
        else startForeground(41, notification)
        ResidentQwenModel.worker.execute {
            try { ResidentQwenModel.load(applicationContext) }
            catch (e: Exception) { Log.e("PhoneMicModel", "Local model preparation failed", e) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop-recording") {
            ((application as MainApplication).reactHost.currentReactContext?.getNativeModule("PhoneMic") as? PhoneMicModule)?.stopSpeech()
        }
        if (intent?.hasExtra("headset") == true) {
            microphoneReady = intent.getBooleanExtra("headset", false) && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= 30) {
                val base = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
                try { startForeground(41, notification, base or (if (microphoneReady) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)) }
                catch (error: SecurityException) {
                    microphoneReady = false
                    startForeground(41, notification, base)
                    Log.w("PhoneMicAudio", "Background microphone could not be armed", error)
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun updateRecording(value: Boolean) {
        notificationBuilder.setContentText(if (value) "正在录音" else "语音键盘待机中").clearActions()
        if (value) {
            val stop = PendingIntent.getService(this, 1, Intent(this, ModelKeepAliveService::class.java).setAction("stop-recording"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notificationBuilder.addAction(android.R.drawable.ic_media_pause, "结束录音", stop)
        }
        notification = notificationBuilder.build()
        getSystemService(NotificationManager::class.java).notify(41, notification)
    }
    override fun onDestroy() { microphoneReady = false; instance = null; super.onDestroy() }
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        writer.println("PhoneMIC local microphone standby=$microphoneReady modelReady=${ResidentQwenModel.isReady()}")
        val module = (application as MainApplication).reactHost.currentReactContext?.getNativeModule("PhoneMic") as? PhoneMicModule
        writer.println(module?.headsetDiagnostics() ?: "React context unavailable")
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        @Volatile var microphoneReady = false
            private set
        @Volatile private var instance: ModelKeepAliveService? = null
        fun recording(value: Boolean) {
            val service = instance ?: return
            service.mainExecutor.execute { if (instance === service) service.updateRecording(value) }
        }
        fun configureHeadset(context: Context, enabled: Boolean) {
            if (enabled && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            try { ContextCompat.startForegroundService(context, Intent(context, ModelKeepAliveService::class.java).putExtra("headset", enabled)) }
            catch (error: IllegalStateException) { Log.w("PhoneMicAudio", "Headset standby requires a foreground launch", error) }
        }
    }
}

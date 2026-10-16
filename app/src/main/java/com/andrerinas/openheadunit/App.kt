package com.andrerinas.openheadunit

import androidx.appcompat.app.AppCompatDelegate
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.UserManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.multidex.MultiDex
import com.andrerinas.openheadunit.main.BackgroundNotification
import com.andrerinas.openheadunit.aap.AapNavigation
import com.andrerinas.openheadunit.ssl.ConscryptInitializer
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.Settings
import android.os.SystemClock
import java.io.File

class App : Application() {

    private val component: AppComponent by lazy {
        AppComponent(this)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // [32bit-debug] 尽早安装崩溃日志钩子：把未捕获异常写入公共 Downloads 和应用私有目录，
        // 便于无 adb 环境下从文件管理器/U盘取回崩溃堆栈
        installCrashFileLogger()


        // Enable vector drawable support on older Android versions
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        if (ConscryptInitializer.isNeededForTls12()) {
            ConscryptInitializer.initialize()
        }

        // Root support
        component.suExecutor.register()

        if (isUserUnlocked()) {
            val settings = Settings(this) // Create a Settings instance
            AppLog.init(settings, this) // Initialize AppLog with settings for conditional logging

            // Sync auto-start settings to device-protected storage so that
            // BootCompleteReceiver, UsbAttachedActivity, and AutoStartReceiver
            // can read them during locked boot (before user unlock)
            Settings.syncAutoStartOnBootToDeviceStorage(this, settings.autoStartOnBoot)
            Settings.syncAutoStartOnUsbToDeviceStorage(this, settings.autoStartOnUsb)
            Settings.syncAutoStartOnWifiToDeviceStorage(this, settings.autoStartOnWifi)
            Settings.syncAutoStartWifiSsidToDeviceStorage(this, settings.autoStartWifiSsid)
            Settings.syncAutoStartBtMacsToDeviceStorage(this, settings.autoStartBluetoothDeviceMacs)

            // Apply app theme (runs the live manager when dynamic, or when a saved place
            // can force the app theme even over a static base).
            AppThemeManager.reapply(this, settings)
        } else {
            AppLog.init(null, this) // Initialize with default logging if locked
            AppLog.w("App started in Direct Boot mode (locked). Settings access deferred.")
        }

        // [32bit-debug] 进程启动标记。
        // 2026-10-16：上一份日志里 "AapService creating..." 出现 10 次而
        // "AapService destroying..." 出现 0 次 —— 说明每一轮"闪退"之后进程都是被硬杀掉的
        // （SIGKILL/force-stop 不走 onDestroy）。这一行给出确凿的进程边界，配合每行的
        // p<pid>@ 前缀，可以直接把整份日志切成"第 N 轮进程"。
        AppLog.i("========== 进程启动 PROCESS START pid=" + android.os.Process.myPid()
                + " uptime=" + android.os.SystemClock.uptimeMillis() + " ==========")

        // [32bit-debug] 全局 Activity 生命周期探针。
        // 此前只在 MainActivity 上打点，覆盖不到三种情况：
        //   ① 本应用其它 Activity（UsbAttachedActivity / IcocaProjectionActivity / AutomationActivity）
        //      被结束；② 同一进程内出现第二个 MainActivity 实例；③ 不同 taskId 上的实例。
        // 这里统一记录，并带上 taskId / isFinishing / changingConfigurations —— 用于区分
        // "系统侧外部 finish"、"配置变更重建"、"任务被移除"。
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private fun tag(a: android.app.Activity): String =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) a.taskId.toString() else "?"

            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {
                AppLog.i("ACT-CREATE  " + a.javaClass.simpleName + " task=" + tag(a))
            }

            override fun onActivityStarted(a: android.app.Activity) {
                AppLog.i("ACT-START   " + a.javaClass.simpleName + " task=" + tag(a))
            }

            override fun onActivityStopped(a: android.app.Activity) {
                AppLog.i("ACT-STOP    " + a.javaClass.simpleName + " task=" + tag(a)
                        + " isFinishing=" + a.isFinishing)
            }

            override fun onActivityDestroyed(a: android.app.Activity) {
                AppLog.i("ACT-DESTROY " + a.javaClass.simpleName + " task=" + tag(a)
                        + " isFinishing=" + a.isFinishing
                        + " configChange=0x" + Integer.toHexString(a.changingConfigurations))
            }

            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
        })

        if (ConscryptInitializer.isAvailable()) {
            AppLog.i("Conscrypt security provider is active")
        } else if (ConscryptInitializer.isNeededForTls12()) {
            AppLog.w("Conscrypt not available - TLS 1.2 may not work on this device")
        }

        AppLog.d( "native library dir ${applicationInfo.nativeLibraryDir}")

        File(applicationInfo.nativeLibraryDir).listFiles()?.forEach { file ->
            AppLog.d( "   ${file.name}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(defaultChannel, "Headunit Service", NotificationManager.IMPORTANCE_LOW)
            serviceChannel.description = "Persistent service notification"
            serviceChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(serviceChannel)

            val mediaChannel = NotificationChannel(BackgroundNotification.mediaChannel, "Media Playback", NotificationManager.IMPORTANCE_LOW)
            mediaChannel.setSound(null, null)
            mediaChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(mediaChannel)

            AapNavigation.createNotificationChannel(this)

            val bootChannel = NotificationChannel(bootStartChannel, "Boot Auto-Start", NotificationManager.IMPORTANCE_HIGH)
            bootChannel.description = "Shown once after boot to open the app"
            bootChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(bootChannel)
        }

        // Register the main broadcast receiver safely for Android 14+ using ContextCompat
        ContextCompat.registerReceiver(this, AapBroadcastReceiver(), AapBroadcastReceiver.filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun isUserUnlocked(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = getSystemService(Context.USER_SERVICE) as UserManager
            userManager.isUserUnlocked
        } else {
            true
        }
    }

    /**
     * [32bit-debug] 全局崩溃日志落盘。
     * 优先写入公共 Downloads（Android 10+ 走 MediaStore，无需存储权限），
     * 同时写应用私有外部目录作为兜底。堆栈文件可通过车机文件管理器 / U盘拷出。
     */
    private fun installCrashFileLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val sb = StringBuilder()
                sb.append("===== CRASH ").append(ts)
                    .append(" thread=").append(t.name).append('\n')
                sb.append(android.util.Log.getStackTraceString(e)).append('\n')
                // 1) 应用私有外部目录（无任何权限要求）
                try {
                    getExternalFilesDir(null)?.let { dir ->
                        java.io.File(dir, "miauto_crash_$ts.log").writeText(sb.toString())
                    }
                } catch (_: Throwable) {
                }
                // 2) 公共 Downloads（API 29+ MediaStore，无需权限）
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,
                                "miauto_crash_$ts.log")
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri = contentResolver.insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            contentResolver.openOutputStream(uri)?.use { os ->
                                os.write(sb.toString().toByteArray())
                                os.flush()
                            }
                            values.clear()
                            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                            contentResolver.update(uri, values, null, null)
                        }
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        const val defaultChannel = "headunit_service_v2"
        const val bootStartChannel = "headunit_boot_start"
        val appStartTime = SystemClock.elapsedRealtime()
        var appThemeManager: AppThemeManager? = null
        var isPiPActive = false

        @Volatile
        var instance: App? = null
            private set


        fun get(context: Context): App {
            return context.applicationContext as App
        }
        fun provide(context: Context): AppComponent {
            return get(context).component
        }
    }
}

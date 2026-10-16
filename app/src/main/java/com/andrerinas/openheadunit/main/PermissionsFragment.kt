package com.andrerinas.openheadunit.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.PermissionRowBinder
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/**
 * Settings entry point mirroring the wizard's permissions step, so users who already finished
 * onboarding can review and grant permissions later. Both surfaces share [PermissionRowBinder]
 * and the [com.andrerinas.openheadunit.utils.AppPermissions] registry.
 *
 * 2026-10-15 新增「本地 ADB 自授权」区块：恒晨 ROM 对侧载应用拦截热点配置接口
 * （getWifiApConfiguration/setWifiApEnabled SecurityException），需要
 * NETWORK_SETTINGS / OVERRIDE_WIFI_CONFIG / TETHER_PRIVILEGED 三个 development
 * 权限（zlink6-hengchen 同款）。车机无电脑可达，app 内置 ADB 客户端连本机
 * adbd（127.0.0.1:5555）自动 pm grant —— 前置条件是工程模式开启「网络 ADB」。
 */
class PermissionsFragment : Fragment() {

    private var binder: PermissionRowBinder? = null
    private var adbStatusView: TextView? = null
    private var adbPermsView: TextView? = null
    private var adbLogView: TextView? = null
    private var adbLogScroll: android.widget.ScrollView? = null
    private var adbPolling = false

    private val normalLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { binder?.rebind() }
    private val specialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { binder?.rebind() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_permissions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        val list = view.findViewById<LinearLayout>(R.id.permissions_container)
        binder = PermissionRowBinder(requireActivity(), list, normalLauncher, specialLauncher)
        view.findViewById<MaterialButton>(R.id.enable_all_button).setOnClickListener {
            binder?.requestAllMissing()
        }
        adbStatusView = view.findViewById(R.id.adb_grant_status)
        adbPermsView = view.findViewById(R.id.adb_grant_perms)
        adbLogView = view.findViewById(R.id.adb_grant_log)
        adbLogScroll = view.findViewById(R.id.adb_grant_log_scroll)
        view.findViewById<MaterialButton>(R.id.adb_grant_run).setOnClickListener {
            com.miauto.app.LocalAdbGranter.runManual(requireContext().applicationContext)
            startAdbPolling()
        }
        // 闪退元凶黑匣子：UsageStats 的「使用情况访问」授权入口 + 当前状态
        view.findViewById<MaterialButton>(R.id.finish_forensics_open).setOnClickListener {
            val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            runCatching { startActivity(intent) }.onFailure {
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            }
        }
        binder?.rebind()
        updateAdbGrantViews()
        updateForensicsStatus(view)
    }

    /** 「使用情况访问」是否已授权 —— 授权后闪退瞬间的前台应用会被写进日志（黑匣子）。 */
    private fun updateForensicsStatus(view: View) {
        val tv = view.findViewById<android.widget.TextView>(R.id.finish_forensics_status) ?: return
        val granted = runCatching {
            val appOps = requireContext().getSystemService(android.app.AppOpsManager::class.java)
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), requireContext().packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), requireContext().packageName)
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        tv.text = getString(
            if (granted) R.string.finish_forensics_granted else R.string.finish_forensics_not_granted)
    }

    override fun onResume() {
        super.onResume()
        // Refresh after returning from a special-permission settings screen.
        binder?.rebind()
        updateAdbGrantViews()
        view?.let { updateForensicsStatus(it) }
        // 进页面也开一轮刷新：启动时 3 秒后的自动授权过程在这里实时可见
        startAdbPolling()
    }

    /** 立即执行后每 0.5 秒刷新状态与实时日志，连续 60 次（30 秒，授权流程通常几秒内结束）。 */
    private fun startAdbPolling() {
        if (adbPolling) return
        adbPolling = true
        var ticks = 0
        val cb = object : Runnable {
            override fun run() {
                ticks++
                val v = view
                if (v == null) { adbPolling = false; return }
                updateAdbGrantViews()
                val done = com.miauto.app.LocalAdbGranter.statusSummary.startsWith("✓") ||
                    com.miauto.app.LocalAdbGranter.statusSummary.startsWith("✗")
                if (ticks < 60 && !(done && ticks > 6)) {
                    v.postDelayed(this, 500)
                } else {
                    adbPolling = false
                }
            }
        }
        view?.post(cb)
    }

    private fun updateAdbGrantViews() {
        val ctx = context ?: return
        adbStatusView?.text = getString(R.string.adb_grant_status_line, com.miauto.app.LocalAdbGranter.statusSummary)
        val net = granted(ctx, "android.permission.NETWORK_SETTINGS")
        val ovr = granted(ctx, "android.permission.OVERRIDE_WIFI_CONFIG")
        val tether = granted(ctx, "android.permission.TETHER_PRIVILEGED")
        adbPermsView?.text = "NETWORK_SETTINGS: ${mark(net)}   OVERRIDE_WIFI_CONFIG: ${mark(ovr)}   TETHER_PRIVILEGED: ${mark(tether)}"
        // 实时日志：自动授权（启动 3 秒后）与手动执行的每一步都写进 logBuffer
        adbLogView?.text = com.miauto.app.LocalAdbGranter.snapshotLog()
        adbLogScroll?.post { adbLogScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "✗"
    private fun granted(ctx: android.content.Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

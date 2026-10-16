package com.andrerinas.openheadunit.connection

import android.content.Context
import android.content.Intent
import android.view.Surface
import com.andrerinas.openheadunit.icoca.IcocaProjectionActivity
import com.andrerinas.openheadunit.utils.AppLog
import com.miauto.app.IccoaManager
import com.ucar.vehiclesdk.UCarCommon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bridges the MiAuto ICCOA CarLink SDK (a plain Java facade, [IccoaManager]) into DiAuto's
 * reactive architecture.
 *
 * DiAuto drives every consumer from a single `StateFlow` (see `CommManager.connectionState`);
 * the ICCOA SDK instead pushes callbacks on worker threads. This class is the adapter between
 * the two: it implements [IccoaManager.Listener], folds the raw callbacks into one observable
 * [state], and exposes the navigation stream the BYD cluster / HUD outputs consume.
 *
 * MiAuto is ICCOA-only — there is no Android Auto path at runtime — so this bridge (not
 * `CommManager`) is the source of truth for both the home screen and the projection activity.
 */
class IcocaBridge(private val context: Context) : IccoaManager.Listener {

    /** Coarse connection phase, mapped from the SDK's numeric state. */
    enum class Phase {
        /** Not started, or stopped by the user. */
        Idle,

        /** BLE advertising; waiting for a phone to discover us. */
        Advertising,

        /** Phone found us and is pairing / negotiating (PIN exchanged, Wi-Fi or USB negotiated). */
        Pairing,

        /** Session established; the phone may now push video. */
        Connected,
    }

    data class State(
        val phase: Phase = Phase.Idle,
        /**
         * Whether the projection window should be on screen right now.
         *
         * Distinct from [phase]: the phone-side "返回车机" (GoToDesktop, SDK state 6) ends the
         * *projection* but keeps the *session* (audio keeps playing, no re-pairing needed) — the
         * SDK reports it as connection state 6 with phase still Connected. State 7
         * (BackToForeground) asks for the projection window back. Only a real disconnect (state
         * 1/3/5) or fresh establishment (2/4) moves [phase] itself.
         */
        val castActive: Boolean = false,
        /** Pairing code to show on screen, if the SDK handed one out. */
        val pinCode: String? = null,
        val carDeviceName: String? = null,
        /** Last raw SDK log line, kept for the diagnostics screen. */
        val lastLog: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigation = MutableStateFlow<UCarCommon.NavigationInfo?>(null)

    /** Latest navigation snapshot from the phone (ICCOA `NotifyNavigationInfo`). */
    val navigation: StateFlow<UCarCommon.NavigationInfo?> = _navigation.asStateFlow()

    /** The underlying SDK facade. Public so projection / navigation code can reach raw APIs. */
    val manager = IccoaManager(context.applicationContext, this)

    @Volatile
    private var started = false

    /** Starts (or restarts) the ICCOA link: SDK init once, then BLE advertising. */
    fun start() {
        if (started) {
            AppLog.i("ICCOA: start() ignored — already started; restarting advertise")
            manager.start() // init-once inside; falls through to reAdvertise()
            return
        }
        started = true
        // ICCOA 的帧率烘焙进 UCarConfig（init-once），所以只在首次启动时从设置读一次：
        // MiAuto 设置里的「帧率限制」(fps-limit) 对 ICCOA 投屏同样生效，而不是写死。
        manager.setFps(com.andrerinas.openheadunit.App.provide(context).settings.fpsLimit)
        AppLog.i("ICCOA: start()")
        updatePhase(Phase.Advertising)
        manager.start()
    }

    /** Stops advertising and tears the session down, keeping the SDK initialised for a restart. */
    fun stop() {
        if (!started) return
        started = false
        AppLog.i("ICCOA: stop()")
        manager.stop()
        _state.update { it.copy(phase = Phase.Idle, castActive = false, pinCode = null) }
    }

    val isStarted: Boolean get() = started

    /**
     * Drops the current session but keeps the link alive and advertising, so the phone can come
     * straight back without the user restarting the app. Backs the projection screen's disconnect
     * affordance, which must not behave like a power-off.
     */
    fun disconnectSession() {
        AppLog.i("ICCOA: disconnectSession()")
        manager.disconnectSession()
        _state.update { it.copy(phase = Phase.Advertising, pinCode = null) }
    }

    /**
     * User-triggered "refresh pairing code": re-broadcasts immediately so the phone re-prompts,
     * instead of waiting for the 30-second watchdog. The stale code is cleared at once so the
     * screen never shows a code that is already dead.
     */
    fun refreshPairing() {
        AppLog.i("ICCOA: refreshPairing()")
        manager.refreshPairing()
        _state.update { it.copy(pinCode = null) }
    }

    /** Native screen size handed to the SDK; drives the negotiated stream resolution. */
    fun setScreenSize(width: Int, height: Int) = manager.setScreenSize(width, height)

    /**
     * Size of the projection area we declared to the SDK (`videoDisplayWidth/Height`).
     *
     * Touch coordinates must be expressed in this space: the SDK scales them to the phone's own
     * screen with `x / displayWidth * sourceWidth` (see `UibcManager.getTouchCoordinateX`).
     */
    fun displaySize(): Pair<Int, Int> = manager.displayWidth to manager.displayHeight

    /**
     * Sends one touch event to the phone (the SDK's UIBC back-channel), mirroring what the SDK's
     * own `UCarSurfaceView.onTouchEvent` does: raw action, per-pointer id and display-space x/y.
     */
    fun sendTouch(action: Int, count: Int, trackIds: IntArray, xs: IntArray, ys: IntArray): Boolean =
        manager.sendTouchEvent(action, count, trackIds, xs, ys)

    /**
     * Hands the projection [surface] to the SDK. Idempotent for the same surface: the SDK ignores a
     * repeated call for an already-casting surface. A *different* surface however means the old one
     * is gone, and the cast is torn down and re-established on the new one.
     */
    fun setSurface(surface: Surface) = manager.setSurface(surface)

    /**
     * The projection surface went away (activity finished, window rebuilt, screen off).
     *
     * Stopping the cast here is what keeps the video pipeline alive across a surface change: the
     * SDK's decoder writes into that specific BufferQueue, and letting it keep writing into a
     * destroyed one ends the video permanently - black screen, audio still playing, phone still
     * reporting a healthy connection.
     */
    fun onSurfaceDestroyed() = manager.onSurfaceDestroyed()

    /** Last PIN the SDK issued, or null. Useful when the projection activity opens late. */
    fun lastPinCode(): String? = manager.lastPinCode

    // ------------------------------------------------------------------
    // IccoaManager.Listener — called on SDK worker threads, hence the StateFlow.
    // ------------------------------------------------------------------

    override fun onLog(msg: String?) {
        AppLog.i("ICCOA: $msg")
        _state.update { it.copy(lastLog = msg) }
    }

    override fun onConnectionState(stateCode: Int, param: Int) {
        // stop() 之后 SDK worker 线程上可能还有排队中的旧回调；已停止的桥不得改写被
        // stop() 重置过的状态（否则会把 Idle 又拉回 Advertising，待机页给出假状态）。
        if (!started) {
            AppLog.i("ICCOA: connection state $stateCode param=$param ignored — bridge stopped")
            return
        }
        // SDK 真实语义（反编译对照官方 demo 的 packed-switch case 1..7 + SDK 全部 fire 点核实；
        // 注意这是第 1 个 int，不是接口名里的 "newState"）：
        //   1 DISCONNECTED     → 设备断开（param==11 = 用户主动断开：手机端 CarLink 断开或车机端断开按钮）
        //   2 DEVICE_CONNECTED → 设备已连接（会话建立）
        //   3 CONNECT_FAILED   → 连接失败（param=错误码）
        //   4 CAST_STARTED     → 投屏已开始（WFD sink ready）
        //   5 CAST_STOPPED     → 投屏已结束（WFD sink stopped；反编译证实 SDK 内部随后走
        //                          disconnect → state 1，但那条可能延迟到心跳超时才到）
        //   6 GO_TO_DESKTOP    → 手机端「返回车机」：会话保留，仅投屏画面退场
        //   7 BACK_TO_FOREGROUND → 手机端要求投屏画面回来
        val phase = when {
            stateCode == 1 && param == 11 -> Phase.Idle // 用户主动断开：静默待机，等刷新配对码
            stateCode == 1 || stateCode == 3 -> Phase.Advertising // 异常断开/失败：自动重新广播
            // ★ state 5 必须立刻降级，不能等 state 1。实测（2026-10-15，手机端 CarLink 断开）：
            //   车机收到的第一条回调是 CAST_STOPPED，DISCONNECTED 要等很久（心跳超时）才到，
            //   期间 phase 若停留在 Connected，待机页就一直是「手机已连接」，用户点「打开投屏」
            //   会对着一个已死的会话 startCast → 黑屏。视频流停了就是事实上的断开：先降级，
            //   后到的 state 1 再决定终态（param=11 → Idle 静默待机；其他 → 保持 Advertising）。
            //   我们自己 pauseCast（Surface 销毁）引发的 state 5 也没问题：startCast 重启后
            //   onCastStarted 会把 phase 拉回 Connected。
            stateCode == 5 -> Phase.Advertising
            stateCode in 2..7 -> Phase.Connected
            else -> null // -1 等瞬时值：保持现状
        }
        val castActive = when {
            stateCode == 1 || stateCode == 3 || stateCode == 5 || stateCode == 6 -> false
            stateCode == 2 || stateCode == 4 || stateCode == 7 -> true
            else -> null
        }
        AppLog.i("ICCOA: connection state $stateCode param=$param -> ${phase?.name ?: "keep ${_state.value.phase}"}"
                + (castActive?.let { " castActive=$it" } ?: ""))
        if (phase != null || castActive != null) {
            _state.update { current ->
                val newPhase = phase ?: current.phase
                current.copy(
                    phase = newPhase,
                    castActive = castActive ?: current.castActive,
                    pinCode = if (newPhase == Phase.Pairing) current.pinCode else null,
                )
            }
        }
        if (stateCode == 7) {
            // Phone asked to come back to projection. The whole app may be backgrounded at this
            // point (after a GoToDesktop the task went to the back), and a backgrounded
            // MainActivity's collector is paused — so the re-open is done here at process level.
            // singleTask routes to the live instance instead of stacking a second one. Background
            // activity starts need SYSTEM_ALERT_WINDOW on API 29+; MiAuto's overlays already
            // require it, and if it wasn't granted the user can still tap the launcher icon.
            try {
                context.startActivity(
                    IcocaProjectionActivity.intent(context)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (t: Throwable) {
                AppLog.i("ICCOA: BackToForeground startActivity failed: $t")
            }
        }
    }

    /**
     * Negotiation progress (0..100). 100 means the link is up.
     *
     * This is the most reliable "the phone is connected" signal the SDK gives: the numeric state
     * codes can lag or turn into the transient -1, while the progress march to 100 is unambiguous.
     * Reaching it must open the projection screen, because that screen is the only thing that
     * provides the Surface the SDK casts into.
     */
    override fun onConnectProgress(progress: Int) {
        if (progress >= 100) updatePhase(Phase.Connected, castActive = true)
    }

    override fun onPinCode(pinCode: String?, carDeviceName: String?) {
        AppLog.i("ICCOA: pinCode=$pinCode carDeviceName=$carDeviceName")
        _state.update {
            it.copy(phase = Phase.Pairing, castActive = false, pinCode = pinCode, carDeviceName = carDeviceName)
        }
    }

    override fun onCastStarted() {
        AppLog.i("ICCOA: cast started")
        updatePhase(Phase.Connected, castActive = true)
    }

    override fun onNavigation(info: UCarCommon.NavigationInfo?) {
        _navigation.value = info
    }

    /**
     * Moves the phase and keeps the stored PIN honest: a code only means something while the SDK is
     * still pairing.
     *
     * Leaving it up after the session opens would show a code that no longer matches anything, and
     * leaving it up after a drop is worse — the phone is about to be issued a *different* one, so
     * the screen would invite the user to type a code that is already dead. Clearing on every
     * non-pairing phase is what makes the code "refresh itself" across reconnects.
     */
    private fun updatePhase(phase: Phase, castActive: Boolean? = null) {
        _state.update { current ->
            current.copy(
                phase = phase,
                castActive = castActive ?: current.castActive,
                pinCode = if (phase == Phase.Pairing) current.pinCode else null,
            )
        }
    }
}

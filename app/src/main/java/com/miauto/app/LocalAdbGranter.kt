package com.miauto.app

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * 嵌入式本地 ADB 客户端：MiAuto 启动时连 127.0.0.1:5555，通过 ADB 协议执行
 * `pm grant` 给自己授予 NETWORK_SETTINGS / OVERRIDE_WIFI_CONFIG / TETHER_PRIVILEGED，
 * 绕开恒晨 ROM 对侧载应用读热点配置的非标拦截（zlink6-hengchen 的做法就是持有这些权限）。
 *
 * 背景（2026-10-15 诊断结论）：
 *  - 恒晨 MT8163 的 WifiServiceImpl 对 getWifiApConfiguration/setWifiApEnabled 做了非标拦截，
 *    targetSdk 28/23 都报 "App not allowed to read or update stored WiFi ap config"；
 *  - 这些接口的检查点是 NETWORK_SETTINGS / OVERRIDE_WIFI_CONFIG（signature|privileged|development），
 *    development 权限可以由 adb shell 的 pm grant 授予 —— 授权后检查直接放行；
 *  - 电脑够不着车机，所以由 app 自己扮演 adb 客户端（连接本机 adbd，协议同 LADB）。
 *
 * 认证：如果 ro.adb.secure=1（user 版默认），AUTH 环节车机屏幕会弹「允许 USB 调试？」——
 * 在车机屏幕上点允许即可（RSA 密钥对缓存在 app 私有目录，下次免弹）。
 * eng 固件通常 ro.adb.secure=0，免认证直接通。
 *
 * 失败完全无感（静默降级），所有过程写 ICCOA 日志进诊断报告。
 */
object LocalAdbGranter {

    // ---- ADB protocol constants（命令为 4 字符的 little-endian 魔数）----
    private const val A_CNXN = 0x4e584e43 // 'CNXN'
    private const val A_AUTH = 0x41555448 // 'AUTH'
    private const val A_OPEN = 0x4f50454e // 'OPEN'
    private const val A_OKAY = 0x4f4b4159 // 'OKAY'
    private const val A_CLSE = 0x45534c45 // 'CLSE'
    private const val A_WRTE = 0x45545257 // 'WRTE'

    private const val A_VERSION = 0x01000001 // ADB protocol version

    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3

    private const val CONNECT_TIMEOUT_MS = 3000
    private const val IO_TIMEOUT_MS = 20000 // 留足时间给车机屏幕上的人点「允许」

    private val GRANT_COMMANDS = listOf(
        "id",
        "getprop ro.adb.secure",
        "getprop ro.debuggable",
        "pm grant com.miauto.app.debug android.permission.NETWORK_SETTINGS",
        "pm grant com.miauto.app.debug android.permission.OVERRIDE_WIFI_CONFIG",
        "pm grant com.miauto.app.debug android.permission.TETHER_PRIVILEGED",
        // AOA 有线通道：SDK 的 grantAutomaticPermission 调 UsbManager.grantDevicePermission
        // 需要 MANAGE_USB（标准 ROM 是 signature|privileged，pm grant 大概率拒绝——
        // 但恒晨 ROM 非标，试一下无妨；失败则由 requestPermission 弹窗兜底）
        "pm grant com.miauto.app.debug android.permission.MANAGE_USB",
        // MainActivity「连接即消失」诊断：系统侧事件日志里有 am_finishActivity 的发起者
        // （哪个组件/什么 flags 调用的 finish），app 进程内 onDestroy 堆栈看不到这一层
        "logcat -d -b events -t 800 | grep -E \"am_finish|am_crash|am_kill|am_anr|am_proc_died|am_create_activity\" | tail -120",
    )

    @Volatile private var attempted = false
    @Volatile private var running = false

    // ---- 授权过程日志缓冲（权限页实时日志窗口直接显示 snapshotLog()）----
    private val logBuffer = StringBuilder()

    /** 授权过程的最近日志（含自动授权与手动执行），权限页实时显示。 */
    fun snapshotLog(): String = synchronized(logBuffer) { logBuffer.toString() }

    private fun appendLog(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        synchronized(logBuffer) {
            logBuffer.append(ts).append(' ').append(line).append('\n')
            if (logBuffer.length > 20000) logBuffer.delete(0, 8000) // 防无限增长
        }
    }

    /**
     * 授权状态摘要（设置页「无线连接」分类的只读项直接显示此值）。
     * UI 标识：✓ 已授权 / ✗ 未授权 + 原因 / 未尝试。
     */
    @Volatile
    var statusSummary: String = "未尝试（app 启动后自动执行）"
        private set

    private fun checkGranted(ctx: android.content.Context, perm: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 主入口：后台线程执行，失败静默。同一进程只尝试一次。 */
    fun runAsync(appCtx: android.content.Context, log: (String) -> Unit) {
        if (attempted) return
        attempted = true
        Thread {
            try {
                run(appCtx) { msg -> appendLog(msg); log(msg) }
            } catch (t: Throwable) {
                appendLog("✗ 异常: ${t.javaClass.simpleName}: ${t.message}")
                statusSummary = "✗ 异常: ${t.javaClass.simpleName}: ${t.message}"
                log("本地ADB: 失败（静默降级）: ${t.javaClass.simpleName}: ${t.message}")
            }
        }.apply { name = "LocalAdbGranter" }.start()
    }

    /**
     * 手动触发（设置页「执行授权」按钮）：绕过 attempted 一次性限制，可重复执行。
     * 重入保护：上一次还没跑完时忽略。日志走 AppLog（进诊断报告）+ 实时缓冲（权限页显示）。
     */
    fun runManual(appCtx: android.content.Context) {
        if (running) return
        running = true
        appendLog("──── 手动执行授权 ────")
        Thread {
            try {
                run(appCtx) { msg -> appendLog(msg); com.andrerinas.openheadunit.utils.AppLog.i("LocalADB: $msg") }
            } catch (t: Throwable) {
                appendLog("✗ 异常: ${t.javaClass.simpleName}: ${t.message}")
                statusSummary = "✗ 异常: ${t.javaClass.simpleName}: ${t.message}"
            } finally {
                running = false
            }
        }.apply { name = "LocalAdbGranterManual" }.start()
    }

    private fun run(appCtx: android.content.Context, log: (String) -> Unit) {
        running = true
        try {
            // ① 本机 adbd 的 unix abstract socket「adbd」——adbd 一直都在监听它，
            //    不依赖任何「网络 ADB」开关（车机自带 adb 客户端走的就是这条路）。
            //    能不能连上取决于 SELinux 是否放行 untrusted_app → adbd，
            //    恒晨这类非标 ROM 策略普遍很松，值得一试且零成本。
            try {
                if (tryUnix(appCtx, log)) return
            } catch (t: Throwable) {
                log("本地ADB: unix @adbd 异常: ${t.javaClass.simpleName}: ${t.message}")
            }
            // ② TCP 兜底扫描：5555（标准 tcpip）、5554、5556-5560（个别 OEM 非标端口）
            for (port in intArrayOf(5555, 5554, 5556, 5557, 5558, 5559, 5560)) {
                try {
                    if (tryPort(appCtx, port, log)) return
                } catch (t: Throwable) {
                    log("本地ADB: 端口 $port 异常: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            statusSummary = "✗ 本机 adbd 无可用入口（unix @adbd 与 TCP 5555-5560 均失败）——找车机的刷机/升级口用电脑 adb 试试"
            log("本地ADB: $statusSummary。")
        } finally {
            running = false
        }
    }

    /** ① 本机 adbd 的 abstract unix socket（localabstract:adbd）。 */
    private fun tryUnix(appCtx: android.content.Context, log: (String) -> Unit): Boolean {
        val socket = android.net.LocalSocket()
        try {
            socket.connect(android.net.LocalSocketAddress("adbd", android.net.LocalSocketAddress.Namespace.ABSTRACT))
        } catch (t: Throwable) {
            log("本地ADB: unix @adbd 连不上（${t.javaClass.simpleName}）")
            return false
        }
        socket.soTimeout = IO_TIMEOUT_MS
        val input = BufferedInputStream(socket.inputStream, 8192)
        val output = BufferedOutputStream(socket.outputStream, 8192)
        try {
            return session(appCtx, input, output, "unix:@adbd", log)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun tryPort(appCtx: android.content.Context, port: Int, log: (String) -> Unit): Boolean {
        Socket().use { socket ->
            try {
                socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
            } catch (t: Throwable) {
                log("本地ADB: 127.0.0.1:$port 连不上（${t.javaClass.simpleName}）")
                return false
            }
            socket.soTimeout = IO_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val output = BufferedOutputStream(socket.getOutputStream(), 8192)
            return session(appCtx, input, output, "127.0.0.1:$port", log)
        }
    }

    /** ADB 会话：CNXN/AUTH 握手 → 执行授权命令 → 落状态。返回 true 表示已连上并执行。 */
    private fun session(
        appCtx: android.content.Context,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        label: String,
        log: (String) -> Unit,
    ): Boolean {
        log("本地ADB: 已连上 $label，发送 CNXN…")
        writeMessage(output, A_CNXN, A_VERSION, 4096 * 1024, "host::features=shell_v2".toByteArray())

        var authorized = false
            var deadline = System.currentTimeMillis() + IO_TIMEOUT_MS
            val keyPair = getOrCreateKeyPair()
            var tokenCount = 0
            while (System.currentTimeMillis() < deadline) {
                val msg = readMessage(input) ?: throw IllegalStateException("adbd 关闭了连接")
                when (msg.cmd) {
                    A_CNXN -> { authorized = true; break }
                    A_AUTH -> when (msg.arg0) {
                        AUTH_TOKEN -> {
                            tokenCount++
                            if (tokenCount == 1) {
                                val sig = Signature.getInstance("SHA1withRSA")
                                sig.initSign(keyPair.private)
                                sig.update(msg.data)
                                log("本地ADB: 收到 AUTH TOKEN → 发送签名")
                                writeMessage(output, A_AUTH, AUTH_SIGNATURE, 0, sig.sign())
                                output.flush()
                            } else {
                                // 签名未被接受（密钥未注册）：改发公钥，车机屏幕会弹
                                // 「允许 USB 调试？」——本机场景用户就在屏幕旁，点允许即可
                                log("本地ADB: 签名未被接受 → 发送公钥注册（车机屏幕将弹授权框，请点「允许」）")
                                writeMessage(output, A_AUTH, AUTH_RSAPUBLICKEY, 0,
                                    androidPubKey(keyPair.public as RSAPublicKey))
                                output.flush()
                            }
                        }
                        AUTH_RSAPUBLICKEY -> {
                            log("本地ADB: 服务器请求公钥")
                        }
                        else -> log("本地ADB: AUTH type=${msg.arg0}")
                    }
                    else -> log("本地ADB: 连接期意外消息 cmd=0x${msg.cmd.toString(16)}")
                }
            }
            if (!authorized) {
                statusSummary = "✗ 认证超时——若车机屏幕弹了「允许 USB 调试」请点允许后重启 app 重试"
                log("本地ADB: $statusSummary")
                return false
            }
            log("本地ADB: ★ CNXN 完成，adbd 已认证（本机可执行命令）")

            // 记录授权前的状态：决定要不要触发「授权后自动重启」。
            // 重启是为了让 SDK 重新 init —— UCarConfig（含热点 SSID）只在 init 时构建一次，
            // 授权后不重启的话 SDK 仍持有启动时读不到配置而用的随机 SSID。
            val wasGranted = checkGranted(appCtx, "android.permission.NETWORK_SETTINGS") &&
                    checkGranted(appCtx, "android.permission.OVERRIDE_WIFI_CONFIG")

            for (command in GRANT_COMMANDS) {
                val result = executeShell(input, output, command, log)
                log("本地ADB: \$ $command\n    → ${result.trim().ifEmpty { "(无输出)" }}")
            }

            // 授权结果落状态（设置页可见）：直接检查运行时权限持有最可靠
            val net = checkGranted(appCtx, "android.permission.NETWORK_SETTINGS")
            val ovr = checkGranted(appCtx, "android.permission.OVERRIDE_WIFI_CONFIG")
            val tether = checkGranted(appCtx, "android.permission.TETHER_PRIVILEGED")
            statusSummary = if (net && ovr) {
                "✓ 已授权（热点配置可读" + if (tether) "，热点创建可用）" else "）"
            } else {
                "✗ 授权未完成（NETWORK_SETTINGS=$net OVERRIDE_WIFI_CONFIG=$ovr TETHER=$tether）"
            }
            if (net && ovr) {
                log("本地ADB: ★★★ 授权成功！$statusSummary")
                if (!wasGranted) {
                    log("本地ADB: 本次为新授权 → 2 秒后自动重启 app，SDK 将带真实热点配置重新 init")
                    scheduleSelfRestart(appCtx)
                } else {
                    log("本地ADB: 之前已授权过，无需重启")
                }
            } else {
                log("本地ADB: $statusSummary")
            }
            return true
    }

    /**
     * 授权成功后的自重启：SDK 配置（热点 SSID/密码）只在进程内 init 一次，授权前启动的
     * 进程拿不到配置，重启后 detectSystemHotspot 才能读到真实值喂给 SDK。
     * 仅在「授权状态发生实际变化」时调用（见调用点），避免每次启动都循环重启。
     */
    private fun scheduleSelfRestart(appCtx: android.content.Context) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val intent = appCtx.packageManager.getLaunchIntentForPackage(appCtx.packageName)
                if (intent != null) {
                    intent.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    appCtx.startActivity(intent)
                }
            } catch (t: Throwable) {
                // 重启失败不影响授权结果，用户手动重开即可
            }
            Runtime.getRuntime().exit(0)
        }, 2000)
    }

    /** 打开一条 shell 会话执行单条命令，返回输出。 */
    private fun executeShell(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        command: String,
        log: (String) -> Unit,
    ): String {
        val localId = 1
        writeMessage(output, A_OPEN, localId, 0, "shell:$command".toByteArray())
        output.flush()

        val sb = StringBuilder()
        var remoteId = -1
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            val msg = readMessage(input) ?: throw IllegalStateException("连接中断")
            when (msg.cmd) {
                A_OKAY -> remoteId = msg.arg0
                A_WRTE -> {
                    sb.append(String(msg.data, Charsets.UTF_8))
                    writeMessage(output, A_OKAY, localId, remoteId)
                    output.flush()
                }
                A_CLSE -> return sb.toString()
                else -> log("本地ADB: shell 期意外消息 cmd=0x${msg.cmd.toString(16)}")
            }
        }
        return sb.toString()
    }

    // ---- 消息收发 ----

    private class AdbMessage(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun writeMessage(out: BufferedOutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val header = ByteArray(24)
        putInt(header, 0, cmd)
        putInt(header, 4, arg0)
        putInt(header, 8, arg1)
        putInt(header, 12, data.size)
        putInt(header, 16, data.sumOf { it.toInt() and 0xff })
        putInt(header, 20, cmd.toInt() xor -0x1)
        out.write(header)
        if (data.isNotEmpty()) out.write(data)
        out.flush()
    }

    private fun readMessage(input: BufferedInputStream): AdbMessage? {
        val header = ByteArray(24)
        if (!readFully(input, header)) return null
        val cmd = getInt(header, 0)
        val arg0 = getInt(header, 4)
        val arg1 = getInt(header, 8)
        val dataLen = getInt(header, 12)
        val data = if (dataLen > 0) ByteArray(dataLen).also { if (!readFully(input, it)) return null } else ByteArray(0)
        return AdbMessage(cmd, arg0, arg1, data)
    }

    private fun readFully(input: BufferedInputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v shr 8) and 0xff).toByte()
        b[off + 2] = ((v shr 16) and 0xff).toByte()
        b[off + 3] = ((v shr 24) and 0xff).toByte()
    }

    private fun getInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    // ---- RSA 密钥对（缓存复用，避免每次启动都让用户点允许）----

    private fun getOrCreateKeyPair(): KeyPair {
        val file = java.io.File(java.io.File("/data/data/com.miauto.app.debug/files"), "local_adb_key.pk8")
        return try {
            if (file.exists()) restoreKeyPair(file) else newKeyPair().also { storeKeyPair(it, file) }
        } catch (t: Throwable) {
            newKeyPair()
        }
    }

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** 密钥对序列化（PKCS#8 + X.509，Base64 包一行文本，够用且免 KeyStore 复杂度）。 */
    private fun storeKeyPair(kp: KeyPair, file: java.io.File) {
        val priv = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        val pub = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        file.writeText(priv + "\n" + pub)
    }

    private fun restoreKeyPair(file: java.io.File): KeyPair {
        val lines = file.readText().split("\n")
        val kf = java.security.KeyFactory.getInstance("RSA")
        val priv = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.decode(lines[0], Base64.NO_WRAP)))
        val pub = kf.generatePublic(java.security.spec.X509EncodedKeySpec(Base64.decode(lines[1], Base64.NO_WRAP)))
        return KeyPair(pub, priv)
    }

    /** 生成 ADB 公钥的 Android 编码（AUTH RSAPUBLICKEY 用；备用，当前流程未走到时忽略）。 */
    @Suppress("unused")
    private fun androidPubKey(publicKey: RSAPublicKey): ByteArray {
        val n = publicKey.modulus
        val e = publicKey.publicExponent.toInt()
        val nBytes = n.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it } // 去符号位
        val r2 = BigInteger.ONE.shiftLeft(nBytes.size * 8 * 2).mod(n)
        val n0inv = BigInteger("2").pow(32).subtract(BigInteger.ONE)
            .multiply(n.modInverse(BigInteger("2").pow(32)).negate())
            .mod(BigInteger("2").pow(32))
        val out = ByteArray(4 + 4 + 256 + 256 + 4)
        putInt(out, 0, nBytes.size / 4)
        putInt(out, 4, n0inv.toInt())
        System.arraycopy(nBytes.reversedArray(), 0, out, 8, 256)
        System.arraycopy(r2.toByteArray().let { if (it.size >= 256) it.copyOfRange(it.size - 256, it.size) else it }.reversedArray(), 0, out, 264, 256)
        putInt(out, 520, e)
        return (Base64.encodeToString(out, Base64.NO_WRAP) + " miauto@local\u0000").toByteArray()
    }
}

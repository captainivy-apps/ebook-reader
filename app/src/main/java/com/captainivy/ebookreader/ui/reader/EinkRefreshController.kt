package com.captainivy.ebookreader.ui.reader

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.View
import java.lang.reflect.Method

/**
 * 墨水屏 EPD 刷新控制器。
 *
 * Android 没有公开的墨水屏 API，各厂商在 `android.view.View` 上打了补丁。
 * 这里用反射依次探测主流厂商机制，命中一个即采用，全部失败则软降级为 no-op
 * （普通设备行为完全不变）。
 *
 * 探测顺序与实现参考 KOReader 的 android-luajit-launcher EPD controllers：
 *  1. Onyx / Qualcomm：静态 `View.setWaveformAndScheme(int,int,int)` +
 *     实例 `View.refreshScreen(x, y, w, h, mode)`，GC 模式 = 98
 *  2. Rockchip RK33xx（Boyue/Likebook）：静态 `View.setByEinkUpdateMode(mode)`，FULL = 1
 *  3. Rockchip RK30xx（Boyue T61/T62）：`View.requestEpdMode(View$EINK_MODE, boolean)`
 *  4. NTX / Freescale（Tolino/Nook）：`View.postInvalidateDelayed(delay, x, y, w, h, mode)`，
 *     FULL|GC16 = 34
 *  5. Hisense（A5/A7/A9 等）：系统服务 `"epd"`（`com.hmct.epd.IEpdManager`），
 *     `forceClear()` 事务号 = 0x15（无需 root）
 */
class EinkRefreshController private constructor() {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var strategy: Strategy = discover()

    /** 是否存在可用的厂商刷新接口 */
    val available: Boolean get() = strategy.available

    /**
     * 用 Activity/Fragment 的 Context 补充探测需要 Context 的机制（如 Hisense 的 `"epd"` 服务）。
     * 应在使用 [available] / [deviceSummary] 之前调用。视 View 补丁类机制无需此步。
     */
    fun attach(context: Context) {
        if (strategy.available) return
        val binder = runCatching { serviceManagerGet(EPD_SERVICE) }.getOrNull()
        val service = runCatching { context.getSystemService(EPD_SERVICE) }.getOrNull()
        if (binder == null && service == null) return
        strategy = HisenseStrategy(binder, service)
        Log.i(TAG, "e-ink refresh strategy: ${strategy.name}")
    }

    /** 供界面显示的设备描述：厂商 机型 · 检测到的刷新接口 */
    fun deviceSummary(): String {
        val hardware = listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
            .distinct()
            .joinToString(" ")
        val eink = strategy.friendly
        return if (hardware.isEmpty()) eink else "$hardware · $eink"
    }

    /**
     * 请求一次全屏清刷（清残影）。命中厂商接口时返回 true，
     * 非墨水屏或接口不可用时返回 false（调用方无需额外处理）。
     */
    fun fullRefresh(view: View): Boolean {
        if (view.width <= 0 || view.height <= 0) return false
        return strategy.refresh(view, handler)
    }

    private interface Strategy {
        val name: String
        val friendly: String
        val available: Boolean
        fun refresh(view: View, handler: Handler): Boolean
    }

    // ------------------------------------------------------------------ Onyx

    private object QualcommStrategy : Strategy {
        override val name = "onyx/qualcomm"
        override val friendly = "Onyx EPD (Qualcomm)"

        private val refreshScreen: Method? = runCatching {
            View::class.java.getMethod(
                "refreshScreen",
                Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE
            )
        }.getOrNull()

        private val setWaveformAndScheme: Method? = runCatching {
            View::class.java.getMethod(
                "setWaveformAndScheme",
                Integer.TYPE, Integer.TYPE, Integer.TYPE
            )
        }.getOrNull()

        override val available: Boolean get() = refreshScreen != null

        override fun refresh(view: View, handler: Handler): Boolean {
            val method = refreshScreen ?: return false
            // 阻止系统层在本次刷新之后再次刷新（否则会出现双刷）
            runCatching { setWaveformAndScheme?.invoke(null, 5, 1, 0) }
            val args = arrayOf<Any>(0, 0, view.width, view.height, GC_MODE)
            handler.postDelayed({
                runCatching { method.invoke(view, *args) }
                    .onFailure { Log.e(TAG, "$name: ${it.message}") }
            }, DELAY_MS)
            return true
        }

        private const val GC_MODE = 98
        private const val DELAY_MS = 60L
    }

    // --------------------------------------------------------- Rockchip RK33xx

    private object Rk33xxStrategy : Strategy {
        override val name = "rockchip/rk33xx"
        override val friendly = "Rockchip RK33xx EPD"

        private val setByEinkUpdateMode: Method? = runCatching {
            View::class.java.getMethod("setByEinkUpdateMode", Integer.TYPE)
        }.getOrNull()

        override val available: Boolean get() = setByEinkUpdateMode != null

        override fun refresh(view: View, handler: Handler): Boolean {
            val method = setByEinkUpdateMode ?: return false
            return runCatching { method.invoke(null, EINK_MODE_FULL); true }
                .getOrElse { Log.e(TAG, "$name: ${it.message}"); false }
        }

        private const val EINK_MODE_FULL = 1
    }

    // --------------------------------------------------------- Rockchip RK30xx

    private object Rk30xxStrategy : Strategy {
        override val name = "rockchip/rk30xx"
        override val friendly = "Rockchip RK30xx EPD"

        @Suppress("UNCHECKED_CAST")
        private val einkModeEnum: Class<Enum<*>>? = runCatching {
            Class.forName("android.view.View\$EINK_MODE") as Class<Enum<*>>
        }.getOrNull()

        private val requestEpdMode: Method? = einkModeEnum?.let { enumCls ->
            runCatching {
                View::class.java.getMethod(
                    "requestEpdMode", enumCls, java.lang.Boolean.TYPE
                )
            }.getOrNull()
        }

        private val fullMode: Any? = einkModeEnum?.enumConstants?.firstOrNull {
            (it as Enum<*>).name == "EPD_FULL"
        }

        override val available: Boolean get() = requestEpdMode != null && fullMode != null

        override fun refresh(view: View, handler: Handler): Boolean {
            val method = requestEpdMode ?: return false
            val mode = fullMode ?: return false
            return runCatching { method.invoke(view, mode, true); true }
                .getOrElse { Log.e(TAG, "$name: ${it.message}"); false }
        }
    }

    // --------------------------------------------------------------- NTX/Nook

    private object NtxStrategy : Strategy {
        override val name = "ntx/freescale"
        override val friendly = "NTX/Freescale EPD"

        private val postInvalidateDelayed: Method? = runCatching {
            View::class.java.getMethod(
                "postInvalidateDelayed",
                java.lang.Long.TYPE,
                Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE
            )
        }.getOrNull()

        override val available: Boolean get() = postInvalidateDelayed != null

        override fun refresh(view: View, handler: Handler): Boolean {
            val method = postInvalidateDelayed ?: return false
            return runCatching {
                method.invoke(view, DELAY_MS, 0, 0, view.width, view.height, MODE_FULL_GC16)
                true
            }.getOrElse { Log.e(TAG, "$name: ${it.message}"); false }
        }

        private const val DELAY_MS = 50L
        private const val MODE_FULL_GC16 = 34 // EINK_UPDATE_MODE_FULL(32) | EINK_WAVEFORM_MODE_GC16(2)
    }

    // -------------------------------------------------------------- Hisense

    /**
     * Hisense 墨水屏（A5/A7/A9 等）通过系统服务 `"epd"`（`com.hmct.epd.IEpdManager`）控制刷新。
     * 应用侧无公开 SDK，直接对 Binder 发起 `forceClear()` 事务（事务号 0x15）即可触发全屏清刷，
     * 等价于 `adb shell service call epd 21`。优先用 Binder，失败再退回对服务对象反射调用 `forceClear`。
     */
    private class HisenseStrategy(
        private val binder: IBinder?,
        private val service: Any?
    ) : Strategy {
        override val name = "hisense"
        override val friendly = "Hisense EPD (hmct)"

        override val available: Boolean get() = binder != null || service != null

        override fun refresh(view: View, handler: Handler): Boolean {
            binder?.let { if (transactForceClear(it)) return true }
            service?.let {
                val ok = runCatching {
                    it.javaClass.getMethod("forceClear").invoke(it)
                    true
                }.getOrElse { e -> Log.e(TAG, "$name forceClear: ${e.message}"); false }
                if (ok) return true
            }
            return false
        }

        private fun transactForceClear(binder: IBinder): Boolean = runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(IE_PD_MANAGER)
                binder.transact(FORCE_CLEAR, data, reply, 0)
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
            }
            true
        }.getOrElse { e -> Log.e(TAG, "$name transact: ${e.message}"); false }

        private companion object {
            const val IE_PD_MANAGER = "com.hmct.epd.IEpdManager"
            const val FORCE_CLEAR = 0x15
        }
    }

    // ----------------------------------------------------------------- no-op

    private object NoOpStrategy : Strategy {
        override val name = "none"
        override val friendly = "非墨水屏"
        override val available = false
        override fun refresh(view: View, handler: Handler): Boolean = false
    }

    companion object {
        private const val TAG = "EinkRefresh"

        /** Hisense 墨水屏的 EPD 系统服务名 */
        private const val EPD_SERVICE = "epd"

        @Volatile
        private var instance: EinkRefreshController? = null

        fun get(): EinkRefreshController = instance ?: synchronized(this) {
            instance ?: EinkRefreshController().also { instance = it }
        }

        /** 反射获取系统服务 Binder（`android.os.ServiceManager#getService`） */
        private fun serviceManagerGet(name: String): IBinder? = runCatching {
            val cls = Class.forName("android.os.ServiceManager")
            val getService = cls.getMethod("getService", String::class.java)
            getService.invoke(null, name) as? IBinder
        }.getOrNull()

        private fun discover(): Strategy {
            val candidates = listOf<Strategy>(
                QualcommStrategy, Rk33xxStrategy, Rk30xxStrategy, NtxStrategy
            )
            val found = candidates.firstOrNull { it.available }
            if (found == null) {
                Log.i(TAG, "no e-ink refresh API detected, using no-op strategy")
                return NoOpStrategy
            }
            Log.i(TAG, "e-ink refresh strategy: ${found.name}")
            return found
        }
    }
}

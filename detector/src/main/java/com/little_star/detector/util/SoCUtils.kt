package com.little_star.detector.util

import java.io.File

/**
 * SoC（System on Chip）检测工具类
 *
 * 通过读取 Android 系统属性识别设备的 SoC 型号，
 * 用于 AOT 预编译模型的自动选择。
 *
 * 主要检测方式：
 * 1. ro.soc.model — 直接返回 SoC 型号（如 "SM8650"），部分设备可用
 * 2. ro.board.platform — 返回平台代号（如 "pineapple"），需映射到 SoC 型号
 * 3. /proc/cpuinfo — 解析 Hardware 字段作为兜底
 */
object SoCUtils {

    private const val TAG = "SoCUtils"

    /**
     * Qualcomm 平台代号到 SoC 型号的映射表
     * key: ro.board.platform 的值
     * value: SoC 型号（与 LiteRT AOT 编译目标对应）
     */
    private val QUALCOMM_PLATFORM_MAP = mapOf(
        // Snapdragon 8 Gen 1 系列
        "taro" to "SM8450",
        "cape" to "SM8450",
        // Snapdragon 8 Gen 2 系列
        "kalama" to "SM8550",
        // Snapdragon 8 Gen 3 系列
        "pineapple" to "SM8650",
        "cliff" to "SM8650",
        // Snapdragon 8 Elite (Gen 4)
        "sun" to "SM8750",
        "sundev" to "SM8750",
        // Snapdragon 8 Elite Gen 5
        "wayne" to "SM8850",
    )

    /**
     * MediaTek 平台代号到 SoC 型号的映射表
     * 目前项目未启用 MediaTek NPU 运行时，预留扩展用
     */
    private val MTK_PLATFORM_MAP = mapOf(
        "mt6983" to "MT6983",   // Dimensity 9000
        "mt6985" to "MT6985",   // Dimensity 9200
        "mt6989" to "MT6989",   // Dimensity 9300
        "mt6991" to "MT6991",   // Dimensity 9400
    )

    /** 缓存检测结果，避免重复读取系统属性 */
    @Volatile
    private var cachedSoCModel: String? = null

    /**
     * 获取设备 SoC 型号
     *
     * 检测优先级：
     * 1. 缓存值
     * 2. ro.soc.model 系统属性（如 "SM8650"）
     * 3. ro.board.platform 系统属性 + 映射表（如 "pineapple" → "SM8650"）
     * 4. /proc/cpuinfo Hardware 字段（兜底）
     *
     * @return SoC 型号字符串，如 "SM8650"；检测失败返回 "unknown"
     */
    fun getSoCModel(): String {
        // 1. 返回缓存
        cachedSoCModel?.let { return it }

        // 2. 尝试直接读取 ro.soc.model
        val socModel = getSystemProperty("ro.soc.model")
        if (!socModel.isNullOrBlank() && socModel != "unknown") {
            cachedSoCModel = socModel.uppercase()
            return cachedSoCModel!!
        }

        // 3. 通过 ro.board.platform + 映射表
        val platform = getSystemProperty("ro.board.platform")
        if (!platform.isNullOrBlank()) {
            val mapped = QUALCOMM_PLATFORM_MAP[platform.lowercase()]
                ?: MTK_PLATFORM_MAP[platform.lowercase()]
            if (mapped != null) {
                cachedSoCModel = mapped
                return mapped
            }
        }

        // 4. 兜底：读取 /proc/cpuinfo
        val cpuInfo = readCpuInfo()
        val hardware = extractHardware(cpuInfo)
        if (hardware != null) {
            // 尝试从 Hardware 字段映射
            val mapped = QUALCOMM_PLATFORM_MAP[hardware.lowercase()]
                ?: MTK_PLATFORM_MAP[hardware.lowercase()]
            if (mapped != null) {
                cachedSoCModel = mapped
                return mapped
            }
            // 直接使用 Hardware 值（可能是 "Qualcomm Technologies, Inc KONA" 之类的）
            cachedSoCModel = hardware
            return hardware
        }

        cachedSoCModel = "unknown"
        return "unknown"
    }

    /**
     * 判断设备是否为 Qualcomm 平台
     */
    fun isQualcomm(): Boolean {
        val model = getSoCModel()
        return model.startsWith("SM") || model.startsWith("QCOM")
    }

    /**
     * 判断设备是否为 MediaTek 平台
     */
    fun isMediaTek(): Boolean {
        val model = getSoCModel()
        return model.startsWith("MT")
    }

    /**
     * 获取 SoC 厂商名称
     * @return "qualcomm"、"mediatek" 或 "unknown"
     */
    fun getVendor(): String = when {
        isQualcomm() -> "qualcomm"
        isMediaTek() -> "mediatek"
        else -> "unknown"
    }

    // ─────────────────────────────────────────────────────────
    // 内部方法
    // ─────────────────────────────────────────────────────────

    /**
     * 读取 Android 系统属性
     * @param propName 属性名称，如 "ro.soc.model"
     * @return 属性值，读取失败返回 null
     */
    private fun getSystemProperty(propName: String): String? {
        return try {
            // 通过反射调用 android.os.SystemProperties.get()
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, propName) as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 读取 /proc/cpuinfo 文件内容
     */
    private fun readCpuInfo(): String {
        return try {
            File("/proc/cpuinfo").readText()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 从 /proc/cpuinfo 中提取 Hardware 字段
     */
    private fun extractHardware(cpuInfo: String): String? {
        if (cpuInfo.isBlank()) return null
        // 匹配 "Hardware\t: xxx" 或 "Hardware : xxx"
        val regex = Regex("""Hardware\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
        return regex.find(cpuInfo)?.groupValues?.get(1)?.trim()
    }
}

package com.little_star.viewmodel

/**
 * 检测器状态
 */
sealed class DetectorState {
    /** 空闲状态 */
    data object Idle : DetectorState()

    /** 加载中 */
    data object Loading : DetectorState()

    /** 就绪，可以进行推理 */
    data class Ready(
        /** 缓存命中信息 */
        val cacheInfo: CacheInfo = CacheInfo.NONE
    ) : DetectorState()

    /** 加载失败 */
    data class Error(val message: String) : DetectorState()

    /** 缓存命中类型 */
    enum class CacheInfo {
        /** 未命中缓存 */
        NONE,
        /** JIT 编译缓存命中 */
        JIT,
        /** AOT 预编译命中 */
        AOT
    }
}

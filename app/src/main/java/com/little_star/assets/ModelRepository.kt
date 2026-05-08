package com.little_star.assets

import android.content.Context
import com.little_star.detector.model.TaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 模型扫描结果仓库，按 TaskType 缓存 TaskModelScanner 实例
 *
 * 核心设计：
 * - 单例缓存，进程生命周期内持久
 * - 首次访问时懒创建到 IO 线程，后续复用缓存
 * - 导入/删除后通过 recreate() 原子替换缓存的 scanner
 * - 首页加载完成后可后台预热所有 task 的扫描缓存
 *
 * 缓存只存储目录结构（modelTree），不持有加载的 TFLite 模型对象，
 * 每个 scanner 的内存占用约几十 KB，预热全部 5 个 task 的总开销极小。
 */
object ModelRepository {

    private val scanners = ConcurrentHashMap<TaskType, TaskModelScanner>()
    private val lock = Mutex()

    /**
     * 获取或创建指定任务类型的扫描器
     * - 缓存命中：直接返回
     * - 缓存未命中：创建新扫描器并缓存
     */
    suspend fun getOrCreate(context: Context, taskType: TaskType): TaskModelScanner {
        scanners[taskType]?.let { return it }
        return lock.withLock {
            scanners.getOrPut(taskType) {
                withContext(Dispatchers.IO) {
                    TaskModelScanner(context.applicationContext, taskType)
                }
            }
        }
    }

    /**
     * 获取已缓存的扫描器（非挂起），null 表示尚未加载
     */
    fun getCached(taskType: TaskType): TaskModelScanner? = scanners[taskType]

    /**
     * 重建指定任务类型的扫描器（用于 import/download 等已处于 IO 协程的刷新场景）
     * 原子替换，旧 scanner 不再被 ViewModel 引用后会自动被 GC。
     */
    suspend fun recreate(context: Context, taskType: TaskType): TaskModelScanner {
        val fresh = withContext(Dispatchers.IO) {
            TaskModelScanner(context.applicationContext, taskType)
        }
        scanners[taskType] = fresh
        return fresh
    }

    /**
     * 同步重建扫描器（用于 delete 等非协程调用场景）
     * 与 TaskModelScanner.refresh() 一致的阻塞语义。
     */
    fun recreateBlocking(context: Context, taskType: TaskType): TaskModelScanner {
        val fresh = TaskModelScanner(context.applicationContext, taskType)
        scanners[taskType] = fresh
        return fresh
    }

    /**
     * 清除指定任务类型的缓存（强制下次访问时重建）
     */
    fun invalidate(taskType: TaskType) {
        scanners.remove(taskType)
    }

    /**
     * 清除所有缓存
     */
    fun invalidateAll() {
        scanners.clear()
    }

    /**
     * 后台预热所有任务类型的扫描缓存。
     * 在首页加载完成后调用，让用户点击任意 Task 卡片时无需等待扫描器初始化。
     *
     * 使用 putIfAbsent 防止与 getOrCreate/recreate 的竞态：
     * 若其他线程已为此 TaskType 缓存了 scanner，prewarm 的 scanner 被自动丢弃。
     */
    suspend fun prewarmAll(context: Context) = withContext(Dispatchers.IO) {
        TaskType.entries.forEach { taskType ->
            // 快速路径：已有缓存则跳过
            if (scanners.containsKey(taskType)) return@forEach
            val scanner = TaskModelScanner(context.applicationContext, taskType)
            // putIfAbsent 原子写入：若 put 瞬间其他线程已缓存（recreate / getOrCreate），
            // 丢弃本次预热的 scanner，保证缓存中始终是最新的
            scanners.putIfAbsent(taskType, scanner)
        }
    }
}

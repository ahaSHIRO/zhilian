package com.baiyin.zhilian.data.batch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 批次文件引用：SAF uri 字符串 + 文件名。
 *
 * 刻意是**纯数据**——扫描模块因此不必认识 `DocumentFile`，规则可以在 JVM 里问，
 * 设备相关的读取留在界面侧的 lambda 里。
 */
data class BatchFileRef(val key: String, val name: String)

/** 扫描结果一项：文件 + 预解析 DTO（解析中或非法批次为 null） */
data class ScannedBatch(val file: BatchFileRef, val dto: BatchFileDto?)

/** 目录扫描状态 */
sealed interface ScanState {

    /** 未开始（刚创建、或刚换完目录） */
    data object Idle : ScanState

    /** 首次扫描：文件名骨架已上屏，DTO 并行解析中 */
    data class FirstScan(val files: List<BatchFileRef>) : ScanState

    /** 有结果；重扫成功后也是它——**原子替换**，中途不退回骨架 */
    data class Ready(val batches: List<ScannedBatch>) : ScanState

    /**
     * 目录不可读：授权被撤、目录被删等。
     * 与「尚未授权目录」是两回事：那个是没选过，这个要用户重新选择，故单独一态。
     */
    data class Unreadable(val reason: String) : ScanState
}

/**
 * 批次目录扫描（C4）：把「列目录 → 首次上骨架 → 并行解析 → 上结果」收成一台状态机。
 *
 * 原先这段逻辑散在批次管理页里，且每次 `ON_RESUME` 都直接 `scope.launch { refresh() }`——
 * 没有取消、没有去重，两次扫描交错写同一份列表，旧结果可能覆盖新结果；
 * 首次进入更会因为「观察者注册补发 ON_RESUME」叠加「显式 RESUMED 分支」而**并发扫描两遍**；
 * SAF 抛异常时也没有兜底，直接从协程里逃出去崩掉。
 *
 * 现在由本模块持有「当前这一次」：[start] 先取消在途扫描，结果**原子替换**，
 * 且只有首次（或换目录后）才写骨架态——已就绪后的重扫全程静默，布局不跳。
 */
class BatchDirectoryScan(
    private val listFiles: suspend () -> List<BatchFileRef>,
    private val parse: suspend (BatchFileRef) -> BatchFileDto?,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private var job: Job? = null

    /** 开始一次扫描：取消在途的那次（旧结果绝不覆盖新结果） */
    fun start() {
        job?.cancel()
        job = scope.launch {
            // 只有手上没有可用结果时才上骨架；已就绪后的重扫保持旧列表直到新结果就绪
            val needSkeleton = _state.value !is ScanState.Ready

            val files = try {
                listFiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ScanState.Unreadable(e.message ?: e::class.java.simpleName)
                return@launch
            }

            if (needSkeleton) _state.value = ScanState.FirstScan(files)

            val parsed = coroutineScope {
                files.map { file ->
                    async {
                        val dto = try {
                            parse(file)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        ScannedBatch(file, dto)
                    }
                }.map { it.await() }
            }

            _state.value = ScanState.Ready(parsed.sortedWith(BATCH_ORDER))
        }
    }

    /** 换目录 / 离开页面：取消在途扫描并回到未开始态 */
    fun reset() {
        job?.cancel()
        job = null
        _state.value = ScanState.Idle
    }

    private companion object {
        /** 按 batchOrder 倒序（解析失败的沉底），再按文件名倒序 */
        val BATCH_ORDER: Comparator<ScannedBatch> =
            compareByDescending<ScannedBatch> { it.dto?.batchOrder ?: Int.MIN_VALUE }
                .thenByDescending { it.file.name }
    }
}

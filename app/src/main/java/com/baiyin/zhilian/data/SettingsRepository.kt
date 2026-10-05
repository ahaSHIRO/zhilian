package com.baiyin.zhilian.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.baiyin.zhilian.data.batch.BatchJson
import com.baiyin.zhilian.data.batch.ReconcileDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_BATCH_TREE_URI = "batch_tree_uri"
private const val KEY_BOTTOM_BAR_STYLE = "bottom_bar_style"
private const val KEY_LAST_RECONCILE = "last_reconcile"

// DataStore 单例委托必须是顶层属性，避免多实例异常
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 本机设置仓。依赖注入采用手动构造（ADR：不引入 DI 框架），由 AppContainer 创建。
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    /**
     * 深色模式。[Read.Pending] 表示还没读到盘——消费点此时应跟系统渲染，而不是拿默认值冒充。
     */
    val themeMode: Flow<Read<ThemeMode>> = dataStore.data.map { prefs ->
        when (prefs[stringPreferencesKey(KEY_THEME_MODE)]) {
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            ThemeMode.DARK.name -> ThemeMode.DARK
            else -> ThemeMode.FOLLOW_SYSTEM
        }
    }.asRead()

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[stringPreferencesKey(KEY_THEME_MODE)] = mode.name }
    }

    /**
     * 底栏效果（ADR-0010）。默认液态玻璃；低版本设备由调用方降级为磨砂。
     * [Read.Pending] 时调用方应先用**最便宜**的标准档渲染，读到设置再切。
     */
    val bottomBarStyle: Flow<Read<BottomBarStyle>> = dataStore.data.map { prefs ->
        val stored = prefs[stringPreferencesKey(KEY_BOTTOM_BAR_STYLE)]
        BottomBarStyle.entries.firstOrNull { it.name == stored } ?: BottomBarStyle.LIQUID_GLASS
    }.asRead()

    suspend fun setBottomBarStyle(style: BottomBarStyle) {
        dataStore.edit { it[stringPreferencesKey(KEY_BOTTOM_BAR_STYLE)] = style.name }
    }

    /**
     * Syncthing 批次目录（SAF tree URI）。
     * [Read.Pending] = 还没读到设置；[Read.Value] 里的 null 才是「用户没授权过」。
     * 两者混用会让批次页在首帧喊一句「尚未授权」再跳变。
     */
    val batchTreeUri: Flow<Read<Uri?>> = dataStore.data.map { prefs ->
        prefs[stringPreferencesKey(KEY_BATCH_TREE_URI)]?.let(Uri::parse)
    }.asRead()

    suspend fun setBatchTreeUri(uri: Uri?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(stringPreferencesKey(KEY_BATCH_TREE_URI))
            else prefs[stringPreferencesKey(KEY_BATCH_TREE_URI)] = uri.toString()
        }
    }

    /**
     * 最近一次题库对账的摘要（ADR-0017 / Q8）。有变更时在批次管理页留一条；无则为 null。
     * 用 JSON 存在本机设置里，跨进程保留——用户下次进批次页仍能看到「上次自动更新了什么」。
     */
    val lastReconcile: Flow<Read<ReconcileDigest?>> = dataStore.data.map { prefs ->
        prefs[stringPreferencesKey(KEY_LAST_RECONCILE)]?.let { raw ->
            runCatching { BatchJson.json.decodeFromString<ReconcileDigest>(raw) }.getOrNull()
        }
    }.asRead()

    suspend fun setLastReconcile(digest: ReconcileDigest) {
        dataStore.edit {
            it[stringPreferencesKey(KEY_LAST_RECONCILE)] = BatchJson.json.encodeToString(digest)
        }
    }

    /** 用户看过摘要后清除 */
    suspend fun clearLastReconcile() {
        dataStore.edit { it.remove(stringPreferencesKey(KEY_LAST_RECONCILE)) }
    }
}

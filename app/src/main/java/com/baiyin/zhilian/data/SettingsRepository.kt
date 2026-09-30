package com.baiyin.zhilian.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_BATCH_TREE_URI = "batch_tree_uri"
private const val KEY_BOTTOM_BAR_STYLE = "bottom_bar_style"

// DataStore 单例委托必须是顶层属性，避免多实例异常
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 本机设置仓。依赖注入采用手动构造（ADR：不引入 DI 框架），由 AppContainer 创建。
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        when (prefs[stringPreferencesKey(KEY_THEME_MODE)]) {
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            ThemeMode.DARK.name -> ThemeMode.DARK
            else -> ThemeMode.FOLLOW_SYSTEM
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[stringPreferencesKey(KEY_THEME_MODE)] = mode.name }
    }

    /** 底栏效果（ADR-0010）。默认液态玻璃；低版本设备由调用方降级为磨砂。 */
    val bottomBarStyle: Flow<BottomBarStyle> = dataStore.data.map { prefs ->
        val stored = prefs[stringPreferencesKey(KEY_BOTTOM_BAR_STYLE)]
        BottomBarStyle.entries.firstOrNull { it.name == stored } ?: BottomBarStyle.LIQUID_GLASS
    }

    suspend fun setBottomBarStyle(style: BottomBarStyle) {
        dataStore.edit { it[stringPreferencesKey(KEY_BOTTOM_BAR_STYLE)] = style.name }
    }

    /** Syncthing 批次目录（SAF tree URI），未授权时为 null */
    val batchTreeUri: Flow<Uri?> = dataStore.data.map { prefs ->
        prefs[stringPreferencesKey(KEY_BATCH_TREE_URI)]?.let(Uri::parse)
    }

    suspend fun setBatchTreeUri(uri: Uri?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(stringPreferencesKey(KEY_BATCH_TREE_URI))
            else prefs[stringPreferencesKey(KEY_BATCH_TREE_URI)] = uri.toString()
        }
    }
}

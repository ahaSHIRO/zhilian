package com.baiyin.zhilian.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.baiyin.zhilian.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val KEY_THEME_MODE = "theme_mode"

// DataStore 单例委托必须是顶层属性，避免多实例异常
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 本机设置仓。依赖注入采用手动构造（ADR：不引入 DI 框架），由 MainActivity 直接创建。
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
}

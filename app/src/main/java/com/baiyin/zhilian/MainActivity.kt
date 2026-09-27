package com.baiyin.zhilian

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baiyin.zhilian.data.SettingsRepository
import com.baiyin.zhilian.ui.navigation.ZhilianApp
import com.baiyin.zhilian.ui.theme.ThemeMode
import com.baiyin.zhilian.ui.theme.ZhilianTheme

/**
 * 唯一 Activity。沉浸式规范：onCreate 中先 enableEdgeToEdge() 再 setContent，
 * 全 App 仅此一处设置系统栏样式，见 docs/conventions/edge-to-edge.md。
 */
class MainActivity : ComponentActivity() {

    // 手动构造依赖（不引入 DI 框架），随 Activity 生命周期存在
    private val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.FOLLOW_SYSTEM)
            ZhilianTheme(themeMode = themeMode) {
                ZhilianApp(settingsRepository = settingsRepository)
            }
        }
    }
}

package com.baiyin.zhilian

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baiyin.zhilian.data.Read
import com.baiyin.zhilian.data.ThemeMode
import com.baiyin.zhilian.data.valueOrNull
import com.baiyin.zhilian.ui.navigation.ZhilianApp
import com.baiyin.zhilian.ui.theme.ZhilianTheme

/**
 * 唯一 Activity。沉浸式规范：onCreate 中先 enableEdgeToEdge() 再 setContent；
 * 运行期系统栏样式由 ZhilianTheme 统一接管，见 docs/conventions/edge-to-edge.md。
 */
class MainActivity : ComponentActivity() {

    // 手动构造依赖（不引入 DI 框架），随 Activity 生命周期存在
    private val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by container.settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = Read.Pending)
            // 设置未读到（DataStore 首读是异步的）时按系统态渲染：至少方向是对的，
            // 不拿默认值冒充用户的选择
            ZhilianTheme(themeMode = themeMode.valueOrNull ?: ThemeMode.FOLLOW_SYSTEM) {
                ZhilianApp(container = container)
            }
        }
    }
}

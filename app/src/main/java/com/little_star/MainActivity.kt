package com.little_star

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.little_star.navigation.AppNavigation
import com.little_star.ui.theme.LanguageProvider
import com.little_star.ui.theme.Yolo26TfliteTheme
import com.little_star.util.LanguageManager

/**
 * 应用主 Activity
 * 设置导航系统并启动应用
 */
class MainActivity : ComponentActivity() {

    // 在 Activity 重建时也应用保存的语言设置
    override fun attachBaseContext(newBase: android.content.Context) {
        val language = LanguageManager.getSavedLanguage(newBase)
        super.attachBaseContext(LanguageManager.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            Yolo26TfliteTheme {
                // 语言状态提供者，切换语言时重建整个 UI 树
                LanguageProvider {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        // 创建导航控制器
                        val navController = rememberNavController()

                        // 设置应用导航
                        AppNavigation(navController = navController)
                    }
                }
            }
        }
    }
}

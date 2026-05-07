package com.little_star.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.little_star.MainApplication
import com.little_star.util.LanguageManager
import java.util.Locale

/** 当前应用语言 CompositionLocal */
val LocalAppLanguage = compositionLocalOf { LanguageManager.AppLanguage.ZH }

/** 语言切换回调 CompositionLocal（参数为目标语言） */
val LocalLanguageToggle = compositionLocalOf<((LanguageManager.AppLanguage) -> Unit)> { {} }

/**
 * 语言状态提供者
 * 包裹在整个 UI 树外层，切换语言时重建整个 UI 树
 */
@Composable
fun LanguageProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // 读取保存的语言偏好作为初始值
    val currentLanguage = remember {
        LanguageManager.getSavedLanguage(context)
    }

    // 使用 mutableStateOf 驱动重组
    val languageState = remember { androidx.compose.runtime.mutableStateOf(currentLanguage) }

    // 切换语言的方法（接受目标语言参数）
    val switchLanguage: (LanguageManager.AppLanguage) -> Unit = { newLanguage ->
        // 保存偏好
        LanguageManager.saveLanguage(context, newLanguage)
        // 更新状态触发重组
        languageState.value = newLanguage
        // 更新 Locale
        val locale = LanguageManager.getLocale(newLanguage)
        Locale.setDefault(locale)

        // 同步更新 Application 的 locale，使 ViewModel 层 getString() 返回正确语言
        (context.applicationContext as? MainApplication)?.updateAppLocale(newLanguage)

        // 重建 Activity 以刷新 Context 和 Resources
        (context as? Activity)?.recreate()
    }

    CompositionLocalProvider(
        LocalAppLanguage provides languageState.value,
        LocalLanguageToggle provides switchLanguage
    ) {
        content()
    }
}

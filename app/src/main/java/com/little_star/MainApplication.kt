package com.little_star

import android.app.Application
import android.os.LocaleList
import com.little_star.util.LanguageManager
import java.util.Locale

/**
 * 应用 Application 子类
 * 在 attachBaseContext 中应用保存的语言设置
 * 提供动态更新 Application locale 的方法，供 ViewModel 层 getString() 使用
 */
class MainApplication : Application() {

    override fun attachBaseContext(base: android.content.Context) {
        val language = LanguageManager.getSavedLanguage(base)
        super.attachBaseContext(LanguageManager.wrapContext(base, language))
    }

    /**
     * 更新 Application 的 Locale 配置
     * 切换语言后调用，使 getApplication().getString() 返回正确语言的字符串
     */
    fun updateAppLocale(language: LanguageManager.AppLanguage) {
        val locale = LanguageManager.getLocale(language)
        Locale.setDefault(locale)

        val config = resources.configuration
        config.setLocale(locale)
        val localeList = LocaleList(locale)
        LocaleList.setDefault(localeList)
        config.setLocales(localeList)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}

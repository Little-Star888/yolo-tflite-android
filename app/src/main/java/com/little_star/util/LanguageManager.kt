package com.little_star.util

import android.content.Context
import android.content.ContextWrapper
import android.os.LocaleList
import java.util.Locale

/**
 * 语言管理器
 * 管理应用语言偏好存储和 Locale 切换
 */
object LanguageManager {

    /** 支持的语言 */
    enum class AppLanguage(val code: String, val displayName: String) {
        ZH("zh", "中文"),
        EN("en", "English")
    }

    private const val PREFS_NAME = "app_settings"
    private const val KEY_LANGUAGE = "app_language"

    /** 获取已保存的语言偏好，默认中文 */
    fun getSavedLanguage(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_LANGUAGE, AppLanguage.ZH.code) ?: AppLanguage.ZH.code
        return AppLanguage.entries.firstOrNull { it.code == code } ?: AppLanguage.ZH
    }

    /** 保存语言偏好 */
    fun saveLanguage(context: Context, language: AppLanguage) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    /** 根据 AppLanguage 获取 Locale */
    fun getLocale(language: AppLanguage): Locale = when (language) {
        AppLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.EN -> Locale.US
    }

    /** 包装 Context 以应用指定语言的 Locale */
    fun wrapContext(context: Context, language: AppLanguage): ContextWrapper {
        val locale = getLocale(language)
        Locale.setDefault(locale)

        val config = context.resources.configuration
        config.setLocale(locale)
        val localeList = LocaleList(locale)
        LocaleList.setDefault(localeList)
        config.setLocales(localeList)

        val updatedContext = context.createConfigurationContext(config)
        return object : ContextWrapper(updatedContext) {}
    }
}

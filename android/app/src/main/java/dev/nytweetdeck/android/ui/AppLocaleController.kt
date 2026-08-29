package dev.nytweetdeck.android.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

internal object AppLocaleController {
    val supportedLanguageTags = listOf(
        "ja",
        "en",
        "zh",
        "hi",
        "es",
        "fr",
        "ar",
        "pt",
        "bn",
        "ru",
        "ur",
    )

    private const val PREFERENCES_NAME = "app-locale"
    private const val PREFERENCE_LANGUAGE_TAG = "language-tag"

    fun currentLanguageTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val tags = context.getSystemService(LocaleManager::class.java)
                .applicationLocales
                .toLanguageTags()
            if (tags.isNotBlank()) {
                return normalize(tags.substringBefore(','))
            }
        }
        val stored = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(PREFERENCE_LANGUAGE_TAG, null)
        return normalize(stored ?: Locale.getDefault().toLanguageTag())
    }

    fun apply(activity: Activity, languageTag: String) {
        val normalized = normalize(languageTag)
        if (currentLanguageTag(activity) == normalized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(normalized)
            return
        }
        activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFERENCE_LANGUAGE_TAG, normalized)
            .apply()
        applyLegacyConfiguration(activity, normalized)
        if (!activity.isFinishing && !activity.isDestroyed) {
            activity.recreate()
        }
    }

    fun localizedContext(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(currentLanguageTag(context)))
        return context.createConfigurationContext(configuration)
    }

    private fun normalize(value: String): String {
        val baseTag = value.trim().substringBefore('-').lowercase(Locale.ROOT)
        return supportedLanguageTags.firstOrNull { it == baseTag } ?: "en"
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyConfiguration(context: Context, languageTag: String) {
        val configuration = Configuration(context.resources.configuration)
        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        configuration.setLocale(locale)
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
        context.applicationContext.resources.updateConfiguration(
            configuration,
            context.applicationContext.resources.displayMetrics,
        )
    }
}

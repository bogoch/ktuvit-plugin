package com.yonatan.ktuvit

import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SubtitleAPI
import com.lagradost.cloudstream3.syncproviders.SubtitleRepo

/**
 * CloudStream has no plugin hook for subtitle providers: BasePlugin only exposes
 * registerMainAPI / registerExtractorAPI / registerVideoClickAction, and the list of
 * subtitle providers is a fixed array on AccountManager:
 *
 *     private static final SubtitleRepo[] subtitleProviders;
 *
 * The array cannot grow, so the field itself is swapped for a longer one. Official
 * builds are not minified (isMinifyEnabled = false), so the field keeps its name.
 *
 * This is not a public API. It can break on any app update, which is why every failure
 * is reported instead of being swallowed.
 */
object Injector {
    private const val FIELD_NAME = "subtitleProviders"

    sealed class Result {
        object Injected : Result()
        object AlreadyPresent : Result()
        data class Failed(val reason: String, val error: Throwable? = null) : Result()
    }

    /**
     * The outcome of the last injection attempt, kept so the diagnostics dialog can
     * show it. Plugins load on a background thread, where nothing may touch the UI.
     */
    @Volatile
    var lastResult: Result? = null
        private set

    /**
     * Reads the live provider list back out of the app. This is the only reliable
     * answer to "is Ktuvit actually registered right now".
     */
    fun currentProviders(): List<String> {
        return try {
            val field = AccountManager::class.java.getDeclaredField(FIELD_NAME)
            field.isAccessible = true
            (field.get(null) as? Array<*>)
                ?.filterIsInstance<SubtitleRepo>()
                ?.map { it.idPrefix }
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun inject(api: SubtitleAPI): Result =
        attempt(api).also { lastResult = it }

    private fun attempt(api: SubtitleAPI): Result {
        return try {
            val field = AccountManager::class.java.getDeclaredField(FIELD_NAME)
            field.isAccessible = true

            val current = field.get(null) as? Array<*>
                ?: return Result.Failed("Field '$FIELD_NAME' is not an array")

            val existing = current.filterIsInstance<SubtitleRepo>()
            if (existing.size != current.size) {
                return Result.Failed("Unexpected array content, found ${current.size} entries")
            }
            if (existing.any { it.idPrefix == api.idPrefix }) {
                return Result.AlreadyPresent
            }

            val updated = (existing + SubtitleRepo(api)).toTypedArray()
            field.set(null, updated)

            val verified = (field.get(null) as? Array<*>)
                ?.filterIsInstance<SubtitleRepo>()
                ?.any { it.idPrefix == api.idPrefix } == true

            if (verified) Result.Injected else Result.Failed("Write went through but the provider is missing")
        } catch (e: NoSuchFieldException) {
            Result.Failed("Field '$FIELD_NAME' not found - the app version is probably different", e)
        } catch (e: IllegalAccessException) {
            Result.Failed("The runtime refused to write to the final field", e)
        } catch (e: Throwable) {
            Result.Failed(e.message ?: e.javaClass.simpleName, e)
        }
    }
}

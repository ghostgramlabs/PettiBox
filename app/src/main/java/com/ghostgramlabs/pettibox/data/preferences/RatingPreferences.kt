package com.ghostgramlabs.pettibox.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ratingDataStore by preferencesDataStore(name = "rating_preferences")

/**
 * Decides when the in-app review sheet may appear. The prompt fires at
 * save-count milestones — moments the shelf is demonstrably earning its
 * keep — never more than once per milestone, and with a long cooldown
 * between prompts so a burst of saving can't chain two together. Play
 * itself applies a further quota on top, so a "prompt" may silently not
 * show; we record it as spent either way rather than nag on every launch
 * until the sheet wins the lottery.
 */
@Singleton
class RatingPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val lastMilestoneKey = intPreferencesKey("last_prompted_milestone")
    private val lastPromptAtKey = longPreferencesKey("last_prompt_at")

    /** The milestone a prompt is due for at [totalSaves], or null for none. */
    suspend fun duePrompt(totalSaves: Int): Int? {
        val prefs = context.ratingDataStore.data.first()
        val lastMilestone = prefs[lastMilestoneKey] ?: 0
        val due = MILESTONES.lastOrNull { totalSaves >= it && it > lastMilestone }
            ?: return null
        val lastAt = prefs[lastPromptAtKey] ?: 0L
        val cooledDown = lastAt == 0L || System.currentTimeMillis() - lastAt >= COOLDOWN_MS
        return if (cooledDown) due else null
    }

    suspend fun markPrompted(milestone: Int) {
        context.ratingDataStore.edit { prefs ->
            prefs[lastMilestoneKey] = maxOf(prefs[lastMilestoneKey] ?: 0, milestone)
            prefs[lastPromptAtKey] = System.currentTimeMillis()
        }
    }

    companion object {
        /** 15 saves ≈ "this stuck"; later marks catch long-term converts. */
        val MILESTONES = listOf(15, 75, 250)
        const val COOLDOWN_MS = 30L * 24 * 60 * 60 * 1000
    }
}

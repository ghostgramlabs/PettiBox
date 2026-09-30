package com.ghostgramlabs.pettibox.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ratingDataStore by preferencesDataStore(name = "rating_preferences")

/**
 * Decides when the in-app review sheet may appear. The prompt fires at
 * save-count milestones — moments the shelf is demonstrably earning its
 * keep — and at a few "it just worked" moments (a restore that brought
 * everything back, the third article read offline). Each fires at most
 * once ever, all share one long cooldown
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
    private val spentMomentsKey = stringSetPreferencesKey("spent_happy_moments")
    private val rateCardDismissedAtKey = longPreferencesKey("rate_card_dismissed_at")
    private val rateCardDismissCountKey = intPreferencesKey("rate_card_dismiss_count")
    private val rateCardDoneKey = booleanPreferencesKey("rate_card_done")

    /**
     * Whether the Home "Enjoying PettiBox?" card may show (the caller adds
     * the save-count threshold). It opens the Play listing directly, which
     * Play doesn't rate-limit the way it limits the in-app sheet. Gone for
     * good once tapped; "Not now" hides it for [CARD_SNOOZE_MS], and after
     * [CARD_MAX_DISMISSALS] it stops asking. Never shows within a few days
     * of the in-app sheet, so the two don't stack.
     */
    val rateCardAllowed: Flow<Boolean> = context.ratingDataStore.data.map { prefs ->
        val now = System.currentTimeMillis()
        val dismissedAt = prefs[rateCardDismissedAtKey] ?: 0L
        val lastSheetAt = prefs[lastPromptAtKey] ?: 0L
        prefs[rateCardDoneKey] != true &&
            (prefs[rateCardDismissCountKey] ?: 0) < CARD_MAX_DISMISSALS &&
            (dismissedAt == 0L || now - dismissedAt >= CARD_SNOOZE_MS) &&
            (lastSheetAt == 0L || now - lastSheetAt >= CARD_SHEET_GAP_MS)
    }

    suspend fun snoozeRateCard() {
        context.ratingDataStore.edit { prefs ->
            prefs[rateCardDismissedAtKey] = System.currentTimeMillis()
            prefs[rateCardDismissCountKey] = (prefs[rateCardDismissCountKey] ?: 0) + 1
        }
    }

    suspend fun finishRateCard() {
        context.ratingDataStore.edit { it[rateCardDoneKey] = true }
    }

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

    /**
     * Claims a one-time [moment] (e.g. [MOMENT_RESTORE]) for a prompt:
     * true — and recorded as spent — only if it hasn't been used before
     * and the shared cooldown has passed.
     */
    suspend fun claimHappyMoment(moment: String): Boolean {
        var claimed = false
        context.ratingDataStore.edit { prefs ->
            val spent = prefs[spentMomentsKey].orEmpty()
            val lastAt = prefs[lastPromptAtKey] ?: 0L
            val cooledDown = lastAt == 0L || System.currentTimeMillis() - lastAt >= COOLDOWN_MS
            if (moment !in spent && cooledDown) {
                prefs[spentMomentsKey] = spent + moment
                prefs[lastPromptAtKey] = System.currentTimeMillis()
                claimed = true
            }
        }
        return claimed
    }

    companion object {
        const val MOMENT_RESTORE = "restore"
        const val MOMENT_IMPORT = "import"
        const val MOMENT_OFFLINE_READS = "offline_reads"

        /** Saves before the Home rate card first appears. */
        const val CARD_MIN_SAVES = 10
        const val CARD_MAX_DISMISSALS = 2
        const val CARD_SNOOZE_MS = 21L * 24 * 60 * 60 * 1000
        const val CARD_SHEET_GAP_MS = 3L * 24 * 60 * 60 * 1000

        /** 15 saves ≈ "this stuck"; later marks catch long-term converts. */
        // 5 catches early users who are already happy — many never reach 15.
        val MILESTONES = listOf(5, 15, 75, 250)
        // Play applies its own (stricter, undisclosed) quota on top; a shorter
        // gap here just stops us sitting out chances Play would allow.
        const val COOLDOWN_MS = 14L * 24 * 60 * 60 * 1000
    }
}

package com.ghostgramlabs.pettibox.ui.components

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview

/**
 * Google Play's in-app review sheet. Callers decide *when* (see
 * RatingPreferences); Play decides whether it actually appears and
 * silently skips it when over quota, so this never throws or retries.
 * No "Do you like the app?" pre-question: Play's policy forbids gating
 * the review on the answer.
 */
object ReviewPrompt {
    suspend fun launch(activity: Activity) {
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            manager.launchReview(activity, manager.requestReview())
        }
    }
}

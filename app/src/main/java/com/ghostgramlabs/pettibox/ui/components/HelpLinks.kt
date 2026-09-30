package com.ghostgramlabs.pettibox.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.ghostgramlabs.pettibox.BuildConfig

/**
 * External help content. The share-sheet flow is the app's core gesture
 * and the hardest one to explain in words, so onboarding and Help both
 * point at a short demo video instead of longer prose.
 */
object HelpLinks {

    /** ~30s screen recording of sharing into PettiBox from another app. */
    const val SHARE_DEMO_VIDEO = "https://www.youtube.com/shorts/SH0tnIFicgg"

    /** Opens in the YouTube app or browser. False when neither exists. */
    fun openShareDemo(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHARE_DEMO_VIDEO)))
    }.isSuccess

    /** "Share PettiBox with a friend": the Play link with a one-line pitch. */
    fun shareApp(context: Context): Boolean = runCatching {
        val link = "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}"
        val text = "I keep links, screenshots, PDFs and notes in PettiBox — save from any app, " +
            "read articles offline, and find text inside pictures. Free: $link"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PettiBox")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share PettiBox"))
    }.isSuccess

    const val SUPPORT_EMAIL = "ghostgramlabs@gmail.com"

    /**
     * Compose a support email with the app/device details prefilled —
     * the difference between an answerable report and a "which version
     * are you on?" round trip. False when no email app is installed.
     */
    fun openSupportEmail(context: Context): Boolean = runCatching {
        val subject = "PettiBox ${BuildConfig.VERSION_NAME} — question or problem"
        val body = buildString {
            appendLine()
            appendLine()
            appendLine("——— Please keep this part, it helps me help you ———")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(intent)
    }.isSuccess

    /**
     * The app's Play Store page — the reliable path for an explicit
     * "Rate" tap (the in-app review sheet is quota-limited and silently
     * no-ops, which reads as a broken button). Falls back to the web
     * listing when the Play app is missing.
     */
    fun openPlayListing(context: Context): Boolean {
        val id = BuildConfig.APPLICATION_ID
        val market = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))
        }.isSuccess
        if (market) return true
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
            )
        }.isSuccess
    }
}

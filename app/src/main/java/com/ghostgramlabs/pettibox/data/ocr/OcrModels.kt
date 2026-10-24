package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import android.util.Log
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What reading each language needs on this phone, and getting it.
 *
 * - ML Kit (Chinese, Japanese, Korean, Devanagari): Play services fetches
 *   the model once; the app doesn't grow.
 * - Tesseract: the language's model from tessdata_fast (Apache 2.0), at a
 *   fixed version and checked against its SHA-256 before it's used, plus
 *   the English model read alongside it.
 */
@Singleton
class OcrModels @Inject constructor(@ApplicationContext private val context: Context) {

    /** Tesseract's data folder: it looks for `tessdata/` inside this. */
    val tessRoot: File get() = File(context.filesDir, "ocr")
    private val tessDir: File get() = File(tessRoot, "tessdata").apply { mkdirs() }

    private fun tessFile(model: String) = File(tessDir, "$model.traineddata")

    /** On disk and complete (each is checked when downloaded; a partial one never gets this name). */
    fun tessReady(model: String): Boolean = tessFile(model).length() > 0

    private val recognizers = HashMap<OcrEngine?, TextRecognizer>()

    /** One client per engine, reused. Null engine: the Latin alphabet (built in). */
    @Synchronized
    fun recognizer(engine: OcrEngine?): TextRecognizer = recognizers.getOrPut(engine) {
        when (engine) {
            OcrEngine.MLKIT_DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            OcrEngine.MLKIT_CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrEngine.MLKIT_JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrEngine.MLKIT_KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            OcrEngine.TESSERACT, null -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    suspend fun isReady(lang: OcrLanguage): Boolean = when (lang.engine) {
        OcrEngine.TESSERACT -> tessReady(lang.tessCode!!) && tessReady(OcrLanguages.TESS_ENGLISH)
        else -> runCatching {
            ModuleInstall.getClient(context).areModulesAvailable(recognizer(lang.engine)).await().areModulesAvailable()
        }.getOrDefault(false)
    }

    /** Of [codes], the languages that can be read right now. */
    suspend fun ready(codes: Set<String>): Set<String> =
        codes.filterTo(LinkedHashSet()) { code -> OcrLanguages.of(code)?.let { isReady(it) } == true }

    /** Gets what [lang] needs. True once it can be read; false to try again later (offline, say). */
    suspend fun fetch(lang: OcrLanguage): Boolean {
        if (isReady(lang)) return true
        return when (lang.engine) {
            OcrEngine.TESSERACT -> download(OcrLanguages.TESS_ENGLISH) && download(lang.tessCode!!)
            else -> installMlKit(lang.engine)
        }
    }

    /** Frees the space of Tesseract models no language in [keep] uses. */
    fun removeUnused(keep: Set<String>) {
        val models = keep.mapNotNull { OcrLanguages.of(it)?.tessCode }.toSet()
        val needed = if (models.isEmpty()) emptySet() else models + OcrLanguages.TESS_ENGLISH
        tessDir.listFiles()?.forEach { f ->
            if (f.name.removeSuffix(".traineddata") !in needed) f.delete()
        }
    }

    private suspend fun installMlKit(engine: OcrEngine): Boolean {
        val client = ModuleInstall.getClient(context)
        val api = recognizer(engine)
        return runCatching {
            client.installModules(ModuleInstallRequest.newBuilder().addApi(api).build()).await()
            // The request returns once accepted; the download finishes in Play services.
            repeat(60) {
                if (client.areModulesAvailable(api).await().areModulesAvailable()) return true
                delay(5_000)
            }
            false
        }.onFailure { Log.w(TAG, "ML Kit model for $engine not installed", it) }.getOrDefault(false)
    }

    private suspend fun download(model: String): Boolean = withContext(Dispatchers.IO) {
        if (tessReady(model)) return@withContext true
        val expected = SHA256[model] ?: return@withContext false
        val part = File(tessDir, "$model.part")
        runCatching {
            val conn = URL("$BASE/$model.traineddata").openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            try {
                if (conn.responseCode != 200) return@runCatching false
                val md = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (sha != expected) {
                    Log.w(TAG, "$model: unexpected content, not used")
                    part.delete()
                    return@runCatching false
                }
                part.renameTo(tessFile(model))
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "$model: download failed", it) }.getOrDefault(false).also { if (!it) part.delete() }
    }

    companion object {
        private const val TAG = "PettiBoxOcr"

        /** tessdata_fast at a fixed commit, so the checksums below hold. */
        private const val BASE = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd"

        private val SHA256 = mapOf(
            "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
            "ara" to "e3206d3dc87fd50c24a0fb9f01838615911d25168f4e64415244b67d2bb3e729",
            "ben" to "31163084c279aaebd376216f0c3d5c17ad4b5fee8db49dae79c20000b5de5964",
            "rus" to "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225",
            "tel" to "d10691fddd5b67802e1c12800ebb321d3b8bcd8d24a2ac3ff206f93188c04ab5",
            "tam" to "d02fbec24be4b07e32e80d0ccfc3b6b67a3c5d61c9d0a7c8532677990912c6ec",
            "mal" to "bd05cbf1b197e7810d2903419aedb06f9ef77bfedf50b358673c1d18d707cdb4",
            "kan" to "bd31e6b6ae93271e3bcf5383d306d8eefbb91542937cd6d735a5930c970e61d8",
            "guj" to "fa69658614b4946a9afae8853d67e0689838803dfa3d12c2e35ec53ee6f8df34",
            "pan" to "1ec0907fc3534065ea9ae190c6bb7ec9e5c74fd9d2fa996aaec7407f11ad8131",
            "ori" to "36f3135e61d501a3acfad41f5fe60b8e791274fff4c5375c969fdcca980cdbac",
            "sin" to "bc75c6df2375a30c9f7e759fa7d4b58ae3ecf9ce72668a702d01acb13e551422",
            "urd" to "62e8250ce2a994106e313a82e26a516a39e2cf159d0ce3c5b5008387fd0d555f",
            "fas" to "db1c0a91208aff00d3cf1ed2c1d23f76419afd5f024688b4f71adc3f2ce4a505",
            "ukr" to "d59e53e2bded32f4445f124b4b00240fcac7e8044c003ab822ccb94f0b3db59b",
            "tha" to "294227cc2d1292b0acb28d61d4115c88252b96d466ca90b417cf4cf0c67bf07c",
            "mya" to "02aa6c25cfe9e583fa7b5d4131eac948f962308983f0f397df077dea58212b03",
            "khm" to "47f110575341b322052f3becbefee61a3ecf1ef549352b5d4d33d28afe30d099",
            "ell" to "4fba8a0b461038d51f1c20d043d4f2ac38c4e778f1b90830847f7bd8fa3ba726",
            "heb" to "11f9e43ab227f786352a50f75c94c2e9906f1baba86d93276da19da7ce0904db",
        )
    }
}

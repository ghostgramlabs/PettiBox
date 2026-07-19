# ── Android / Kotlin baseline ────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepattributes SourceFile, LineNumberTable

# ── Room ─────────────────────────────────────────────────────────────────
# Concrete database name (avoids "class * extends RoomDatabase" rule which
# triggers an R8 ConcurrentModificationException in 8.5.x with this graph).
-keep class com.ghostgramlabs.pettibox.data.local.AppDatabase { *; }
-keep class com.ghostgramlabs.pettibox.data.local.AppDatabase_Impl { *; }
-keep class com.ghostgramlabs.pettibox.data.local.** { *; }
-keepclassmembers class com.ghostgramlabs.pettibox.data.local.** {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}
-dontwarn androidx.room.paging.**

# ── Hilt / Dagger ────────────────────────────────────────────────────────
# Hilt generates names by convention; preserve the structure but allow R8 to
# inline / shrink unused parts.
-keep class hilt_aggregated_deps.** { *; }
-keep class **_HiltModules { *; }
-keep class **_HiltModules$* { *; }
-keep class **_GeneratedInjector { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <init>(...);
}
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}

# ── ML Kit text recognition (loads model classes reflectively) ───────────
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# ── Coil ─────────────────────────────────────────────────────────────────
-dontwarn coil.**

# ── Play in-app review ───────────────────────────────────────────────────
# review-ktx references this compile-time-only gms annotation; it never
# ships in any artifact, so R8 just needs telling that's expected.
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite

# ── Jsoup ────────────────────────────────────────────────────────────────
# Called directly (no reflection) — R8 keeps what's referenced; a blanket
# keep only bloats the dex and blocks optimization.
-dontwarn org.jsoup.**

# ── Kotlin coroutines ────────────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.coroutines.flow.**

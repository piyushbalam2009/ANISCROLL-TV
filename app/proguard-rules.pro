# Preserve BuildConfig secrets and flags in minified release APKs
-keep class com.example.BuildConfig { *; }

# Retrofit & dynamic service proxies
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface in.aniscroll.tv.data.remote.** { *; }
-dontwarn retrofit2.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Domain models, Room entities/DAOs, and Moshi classes
-keep class in.aniscroll.tv.domain.model.** { *; }
-keep class in.aniscroll.tv.core.database.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Coil & AndroidSVG decoders
-keep class coil.** { *; }
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**


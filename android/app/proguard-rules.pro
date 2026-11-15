# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class de.tontaube.**$$serializer { *; }
-keepclassmembers class de.tontaube.** { *** Companion; }
-keepclasseswithmembers class de.tontaube.** { kotlinx.serialization.KSerializer serializer(...); }

# PdfBox-Android: optionale Abhängigkeiten (JPEG2000, Gemalto) fehlen absichtlich
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }

# jsoup
-dontwarn org.jspecify.annotations.**
-keeppackagenames org.jsoup.nodes

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

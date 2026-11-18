# Moqi IME ProGuard Rules

-keepclassmembers class com.moqi.im.core.** {
    *;
}
-keepclassmembers class com.moqi.im.engine.** {
    *;
}
-keepclassmembers class com.moqi.im.dict.** {
    *;
}

# Sherpa's JNI bridge resolves configuration fields (e.g. decodingMethod)
# and other Java members by their original names. The bundled 1.13.0 AAR
# ships an empty consumer proguard.txt; native-method defaults alone do not
# protect these fields. Keep the whole JNI surface, including nested configs.
# Lite has no Sherpa dependency, so this rule has no effect on its APK.
-keep class com.k2fsa.sherpa.onnx.** { *; }

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
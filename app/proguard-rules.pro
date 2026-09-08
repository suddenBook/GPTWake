-keep class com.k2fsa.sherpa.onnx.** { *; }
# Kuromoji locates its bundled dictionary relative to these class names.
-keep class com.atilika.kuromoji.** { *; }
-keepclasseswithmembers class * {
    native <methods>;
}

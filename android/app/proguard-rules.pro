# kotlinx.serialization keeps generated serializers reachable via reflection.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.ocrapp.** {
    *** Companion;
}
-keepclasseswithmembers class com.ocrapp.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit interfaces are referenced only reflectively.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

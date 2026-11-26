# Читаемые стектрейсы в крешах релиза
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: сгенерированные сериализаторы находятся через companion
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.rustamguloff.foliolink.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# WorkManager (его тянет Glance) создаёт WorkDatabase_Impl через рефлексию Room:
# без этого правила R8 убирает конструктор, и релиз падает при запуске.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

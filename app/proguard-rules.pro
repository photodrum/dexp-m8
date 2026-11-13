-dontobfuscate

# JNI-мост нельзя ни удалять, ни переименовывать: имена Java-методов зашиты
# в C-функции (Java_ru_ogrpfsense_dexpm8_core_NativeBook_parseFb2).
# -keepclasseswithmembernames здесь НЕ подходит: он разрешает shrinking,
# и R8 выкидывает класс, пока его никто не вызывает из Kotlin.
-keep class ru.ogrpfsense.dexpm8.core.NativeBook { *; }
-keep class ru.ogrpfsense.dexpm8.core.NativeBook$Block { *; }
# JNI к движкам R0 (cpp/engines/probe_jni.c).
-keep class ru.ogrpfsense.dexpm8.reader.EngineProbe { *; }
# JNI сеанса читалки R1 (cpp/engines/reader_jni.cpp): имена методов зашиты в C++.
-keep class ru.ogrpfsense.dexpm8.reader.Engine { *; }

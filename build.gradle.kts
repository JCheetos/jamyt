// Top-level build file
// Las versiones de plugins se declaran aquí (apply false) y se aplican en los módulos
// que las necesiten. Buenas prácticas: fuente única de verdad para versiones.
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.24" apply false
}
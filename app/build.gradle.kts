plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.jamyt"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jamyt"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    // NOTA: A partir de Kotlin 2.0, la versión del Compose Compiler se gestiona
    // a través del plugin `org.jetbrains.kotlin.plugin.compose` declarado arriba.
    // El bloque `composeOptions { kotlinCompilerExtensionVersion = ... }` es obsoleto
    // y no debe usarse con K2.   kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Material XML library (requerida para los temas Theme.Material3.* referenciados en el Manifest).
    // Compose trae su propio MaterialTheme a nivel de código, pero los temas XML base
    // viven en esta dependencia. Versión 1.12.0 es la última estable al momento.
    implementation("com.google.android.material:material:1.12.0")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // ViewModel + StateFlow
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    // Room (persistencia)
    // KSP reemplaza a annotationProcessor para Kotlin: más rápido y oficialmente recomendado.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Cast SDK (Fase 2)
    // Usamos el Default Media Receiver (no requiere registro en Cast Developer Console).
    // Si más adelante queremos un receiver personalizado, registraremos la app
    // con nuestro App ID de Google Cast (ya tenemos la cuenta pagada).
    // IMPORTANTE: play-services-cast (sin "-framework") solo trae las APIs
    // básicas; para CastContext/CastSession/RemoteMediaClient necesitamos
    // play-services-cast-framework.
    implementation("com.google.android.gms:play-services-cast-framework:22.1.0")
    // MediaRouter para el MediaRouteButton (botón Cast estándar en UI).
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
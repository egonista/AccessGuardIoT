import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun cargarClaveAesDesdeConfig(): String {
    val secretsFile = rootProject.file("secrets.properties")
    val props = Properties()
    if (secretsFile.exists()) {
        secretsFile.inputStream().use { props.load(it) }
    }
    val desdeArchivo = props.getProperty("ACCESSGUARD_AES_KEY")?.trim()
    val desdeEnv = System.getenv("ACCESSGUARD_AES_KEY")?.trim()
    val candidata = desdeArchivo?.takeIf { it.isNotEmpty() } ?: desdeEnv?.takeIf { it.isNotEmpty() }
    if (candidata != null) {
        return candidata.take(16).padEnd(16, '0')
    }
    logger.warn("secrets.properties no encontrado; usando clave temporal local de compilación. Ver secrets.properties.example.")
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(rootProject.projectDir.absolutePath.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}

val claveAesBuild: String = cargarClaveAesDesdeConfig()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.example.accessguardiot"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.accessguardiot"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "AES_KEY", "\"$claveAesBuild\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
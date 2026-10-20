import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Charge un fichier .properties optionnel à la racine du projet.
fun loadProps(name: String): Properties = Properties().apply {
    val f = rootProject.file(name)
    if (f.exists()) f.inputStream().use { load(it) }
}

// Signature release : keystore.properties (non versionné, voir README).
val keystoreProps = loadProps("keystore.properties")
// Valeurs par défaut des réglages (optionnel) : bornes.properties.
val bornesProps = loadProps("bornes.properties")

fun quoted(v: String?) = "\"" + (v ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.pubbornes.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pubbornes.player"
        minSdk = 26
        targetSdk = 34
        // À incrémenter à chaque mise à jour (voir README).
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "DEFAULT_DRIVE_FOLDER_ID", quoted(bornesProps.getProperty("driveFolderId")))
        buildConfigField("String", "DEFAULT_API_KEY", quoted(bornesProps.getProperty("apiKey")))
        buildConfigField("String", "DEFAULT_POS_PACKAGE", quoted(bornesProps.getProperty("posPackage")))
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Pas de minification : priorité à la robustesse (pas de risque R8).
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // App installée hors Play Store : on ne bloque pas le build release sur le lint.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

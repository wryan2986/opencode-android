import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.ryan.opencode"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ryan.opencode"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Release signing comes from keystore.properties (gitignored) or the
        // OPENCODE_* environment variables. `scripts/make-keystore.sh` sets it up.
        //
        // Evaluated lazily and tolerantly on purpose: signingConfigs are resolved
        // when the build file is read, so throwing here would break plain
        // `assembleDebug` for anyone who has not made a keystore yet. A missing
        // key therefore falls back to debug signing rather than failing the build.
        val propsFile = rootProject.file("keystore.properties")
        val props = Properties().apply {
            if (propsFile.exists()) propsFile.inputStream().use { load(it) }
        }
        fun secret(key: String, env: String): String? =
            (System.getenv(env) ?: props.getProperty(key, "")).takeIf { it.isNotBlank() }

        val storeFile = secret("storeFile", "OPENCODE_KEYSTORE")
        val storePass = secret("storePassword", "OPENCODE_STORE_PASSWORD")
        val alias = secret("keyAlias", "OPENCODE_KEY_ALIAS")
        val keyPass = secret("keyPassword", "OPENCODE_KEY_PASSWORD")

        if (listOf(storeFile, storePass, alias, keyPass).all { it != null }) {
            create("release") {
                this.storeFile = rootProject.file(storeFile!!)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // sshj is reflection-heavy and ProGuard will strip the JCE provider wiring it
    // relies on, which shows up as a NoSuchAlgorithmException at runtime rather
    // than at build time. Keep its internals.
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // SSH transport. sshj pulls BouncyCastle for its key exchange and host key
    // checks; slf4j-android is the logging facade it binds to (sshj logs through
    // SLF4J and would otherwise be silent). BouncyCastle is pinned explicitly
    // because Android ships a stripped provider under the same package name.
    implementation(libs.sshj)
    implementation(libs.slf4j.android)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}

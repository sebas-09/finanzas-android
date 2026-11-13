plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    
}
android {
    namespace = "ec.finanzas.security"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true; warningsAsErrors = true; lintConfig = rootProject.file("lint.xml") }
}
kotlin { jvmToolchain(17) }
dependencies {
    androidTestImplementation(libs.androidx.test.core)
}


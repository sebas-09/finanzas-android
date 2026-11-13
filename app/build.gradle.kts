plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "ec.finanzas.locales"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "ec.finanzas.locales"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0-f5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Las claves y contraseñas pertenecen al propietario y permanecen fuera del repositorio.
    val releaseStore = providers.environmentVariable("FINANZAS_RELEASE_STORE_FILE").orNull
    val releaseStorePassword = providers.environmentVariable("FINANZAS_RELEASE_STORE_PASSWORD").orNull
    val releaseAlias = providers.environmentVariable("FINANZAS_RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("FINANZAS_RELEASE_KEY_PASSWORD").orNull
    val releaseValues = listOf(releaseStore, releaseStorePassword, releaseAlias, releaseKeyPassword)
    require(releaseValues.all { it.isNullOrBlank() } || releaseValues.all { !it.isNullOrBlank() }) {
        "Configura las cuatro variables FINANZAS_RELEASE_*; no se admite firma parcial."
    }
    val configuredRelease = if (releaseValues.all { !it.isNullOrBlank() }) signingConfigs.create("ownerRelease") {
        storeFile = file(requireNotNull(releaseStore))
        storePassword = releaseStorePassword
        keyAlias = releaseAlias
        keyPassword = releaseKeyPassword
    } else null
    buildTypes {
        release {
            signingConfig = configuredRelease
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint { abortOnError = true; warningsAsErrors = true; lintConfig = rootProject.file("lint.xml") }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.work.runtime)
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:design-system"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:parsers"))
    implementation(project(":feature:ledger"))
    implementation(project(":feature:reconciliation"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:accounts"))
    implementation(project(":feature:settings"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(project(":core:security"))
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.compose.ui.tooling)
}

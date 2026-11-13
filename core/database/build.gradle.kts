plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}
android {
    namespace = "ec.finanzas.database"
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
    
    implementation(project(":core:security"))
    api(project(":core:model"))
    api(project(":feature:ledger"))
    api(project(":feature:dashboard"))
    implementation(project(":feature:reconciliation"))
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.sqlite)
    implementation(libs.sqlcipher)
    ksp(libs.room.compiler)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)

}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
android { sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas") }


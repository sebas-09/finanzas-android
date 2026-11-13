plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "ec.finanzas.capture"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true; warningsAsErrors = true; lintConfig = rootProject.file("lint.xml") }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":feature:parsers"))
    implementation(project(":core:security"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.work.runtime)
}

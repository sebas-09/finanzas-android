plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:model"))
    api(project(":feature:ledger"))
    testImplementation(libs.junit)
    testImplementation(project(":core:testing"))
}


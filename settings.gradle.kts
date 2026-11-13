pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "FinanzasLocales"
include(":app", ":core:model", ":core:database", ":core:security", ":core:design-system", ":core:testing")
include(":feature:onboarding", ":feature:capture", ":feature:parsers", ":feature:ledger")
include(":feature:reconciliation", ":feature:dashboard", ":feature:accounts", ":feature:settings")

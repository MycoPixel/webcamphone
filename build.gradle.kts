// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Kotlin support is built into AGP 9, so the org.jetbrains.kotlin.android plugin is no longer applied.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

plugins {
    // Add this plugin for an application module
    id("com.android.application")

    // Or, if it's a library module, use this instead:
    // id("com.android.library")

    // This plugin is also typically needed for Kotlin support
    id("org.jetbrains.kotlin.android")
}

// Make sure this block is inside the `android { ... }` block
android {
    // ... other settings like compileSdk, defaultConfig

    // Add this buildFeatures block
    buildFeatures {
        viewBinding = true
    }
}

// Ensure you also have these standard dependencies
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // ... other dependencies
}

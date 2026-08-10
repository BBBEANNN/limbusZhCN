// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

extra["VERSION"] = "2.3.2-limbus"
extra["VERSION_CODE"] = 23202
extra["PACKAGE_NAME_32BIT"] = "com.example.limbuszhcn"

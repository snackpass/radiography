plugins {
  id("com.android.library")
  kotlin("android")
}

android {
  compileSdk = 36
  namespace = "com.squareup.radiography"

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  defaultConfig {
    minSdk = 23
  }

  buildFeatures {
    buildConfig = false
  }
}

dependencies {
  implementation("com.squareup.curtains:curtains:1.2.5")
  compileOnly("androidx.compose.ui:ui-tooling-data:1.10.1")
}

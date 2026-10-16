// Top-level build file. Same pinned toolchain as Prism, Perch and Morse (see their notes): newer libraries
// are built with Kotlin 2.2 metadata and don't compile here.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}

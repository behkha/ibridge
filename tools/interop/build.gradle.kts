// Runs the Android app's real protocol code (android/app/.../core) on the JVM, for tools/interop/run.py.
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

val core = "../../android/app/src/main/java/app/ibridge/core"
sourceSets.main {
    kotlin.srcDirs("src")
    kotlin.include("**/AndroidSide.kt", "**/DecodeQr.kt", "**/Proto.kt", "**/Crypto.kt", "**/Pairing.kt", "**/LinkEngine.kt")
    kotlin.srcDir(core)
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")
}
kotlin { jvmToolchain(21) }
application { mainClass.set("AndroidSideKt") }

// `gradlew -p tools/interop decodeQr --args=<png>` prints the text in a pairing-sheet screenshot's QR.
dependencies { implementation("com.google.zxing:javase:3.5.3") }
tasks.register<JavaExec>("decodeQr") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("DecodeQrKt")
}

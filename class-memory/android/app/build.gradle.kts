plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.legacy-kapt")
}

// One application architecture; only model assets/configuration and test identity differ.
// Without these properties the original production package and Zipformer stay unchanged.
val asrModel = providers.gradleProperty("asrModel").getOrElse("xasr")
val asrComparison = providers.gradleProperty("asrComparison").getOrElse("true").toBooleanStrict()
val asrHotwordProfile = providers.gradleProperty("asrHotwordProfile").getOrElse(if (asrModel == "xasr") "data_structure" else "none")
require(asrHotwordProfile in setOf("none", "computer_org", "data_structure")) { "Unknown asrHotwordProfile: $asrHotwordProfile" }
require(asrHotwordProfile == "none" || (asrComparison && asrModel == "xasr")) {
    "ASR hotword profiles require -PasrComparison=true and -PasrModel=xasr; the production package is protected."
}
val asrModels = mapOf(
    "zipformer" to Pair("Zipformer", "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"),
    "xasr" to Pair("X-ASR", "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05"),
    "qwen3" to Pair("Qwen3-ASR", "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"),
)
val (asrModelLabel, asrModelDirectory) = requireNotNull(asrModels[asrModel]) { "Unknown asrModel: $asrModel" }
require(asrComparison || asrModel == "zipformer") { "Alternative models require -PasrComparison=true; the production package is protected." }
val displayModelLabel = when (asrHotwordProfile) {
    "computer_org" -> "$asrModelLabel 计组热词"
    "data_structure" -> "$asrModelLabel 课程热词"
    else -> asrModelLabel
}
val asrAppLabel = "Class Memory"
val modelAssetRoot = if (asrModel == "zipformer") file("src/main/assets") else file(".asr-models/$asrModel")
val requiredModelFiles = when (asrModel) {
    "zipformer" -> listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt", "bpe.vocab")
    "xasr" -> listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt") +
        if (asrHotwordProfile != "none") listOf("bpe.vocab") else emptyList()
    else -> listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx", "silero_vad.onnx", "tokenizer/vocab.json", "tokenizer/merges.txt", "tokenizer/tokenizer_config.json")
}
val stagedAssets = layout.buildDirectory.dir("generated/asr-assets/$asrModel")
val stageAsrAssets by tasks.registering(Sync::class) {
    from("src/main/assets") {
        include("licenses/**")
    }
    from(modelAssetRoot) {
        include("$asrModelDirectory/**")
        if (asrModel == "xasr" && asrHotwordProfile == "none") exclude("$asrModelDirectory/bpe.vocab")
    }
    into(stagedAssets)
    doFirst {
        check(File(modelAssetRoot, asrModelDirectory).isDirectory) {
            "Missing $asrModelDirectory assets. See docs/testing/three-asr-apps.md."
        }
        requiredModelFiles.forEach { relativePath ->
            val asset = File(modelAssetRoot, "$asrModelDirectory/$relativePath")
            check(asset.isFile && asset.length() > 0) { "Missing or empty ASR asset: $relativePath" }
        }
    }
}

android {
    namespace = "com.classmemory.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        applicationId = "com.classmemory.app.publish"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "ASR_MODEL_ID", "\"$asrModel\"")
        buildConfigField("String", "ASR_MODEL_DIR", "\"$asrModelDirectory\"")
        buildConfigField("String", "ASR_MODEL_LABEL", "\"$displayModelLabel\"")
        buildConfigField("String", "ASR_HOTWORD_PROFILE", "\"$asrHotwordProfile\"")
        buildConfigField("boolean", "ASR_COMPARISON", asrComparison.toString())
        buildConfigField("boolean", "PUBLIC_BUILD", "true")
        resValue("string", "app_name", asrAppLabel)
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    sourceSets.getByName("main").assets.setSrcDirs(listOf(stagedAssets))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        }
    }
}

tasks.named("preBuild") { dependsOn(stageAsrAssets) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom); androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui"); implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.room:room-runtime:2.8.4"); implementation("androidx.room:room-ktx:2.8.4"); kapt("androidx.room:room-compiler:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20250517")
}

dependencyLocking {
    lockAllConfigurations()
}

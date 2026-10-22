plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val availableWhisperModels = mapOf(
    "tiny" to "ggml-tiny-q5_1.bin",
    "small" to "ggml-small-q5_1.bin"
)
val selectedWhisperModel = providers.gradleProperty("whisperModel").orElse("tiny").get()
val selectedWhisperModelFile = availableWhisperModels[selectedWhisperModel]
    ?: error("Unknown whisperModel '$selectedWhisperModel'. Choose one of: ${availableWhisperModels.keys.joinToString()}.")
val stagedWhisperAssetDirectory = layout.buildDirectory.dir("generated/whisperAssets/main")
val selectedWhisperModelPath = rootProject.file("models/$selectedWhisperModelFile")
check(selectedWhisperModelPath.isFile) {
    "Whisper weights are missing: $selectedWhisperModelPath. Download the model before building."
}

android {
    namespace = "com.distressdetector"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "30.0.16248370"

    sourceSets.getByName("main").assets.srcDir(stagedWhisperAssetDirectory)

    androidResources {
        noCompress += "bin"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.distressdetector"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        resValue("string", "whisper_model_file", selectedWhisperModelFile)
        resValue("string", "whisper_model_label", selectedWhisperModel)

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

tasks.register<Sync>("stageWhisperModels") {
    from(selectedWhisperModelPath)
    into(stagedWhisperAssetDirectory)
}

tasks.named("preBuild").configure {
    dependsOn("stageWhisperModels")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

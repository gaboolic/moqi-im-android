# 在构建时自动下载语音模型

# 任务: downloadSherpaModels
# 在构建 APK 之前自动下载并解压 Sherpa-onnx 模型到 assets 目录

import java.util.zip.ZipInputStream

val modelsDir = file("src/main/assets/models/sherpa")
val modelName = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23"
val modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$modelName.tar.bz2"
val modelArchive = file("build/sherpa-model.tar.bz2")
val modelMarker = file("$modelsDir/.model-name")

tasks.register<Exec>("downloadSherpaModels") {
    group = "setup"
    description = "Download Sherpa-onnx speech recognition model"
    
    onlyIf {
        !modelMarker.exists() ||
            modelMarker.readText().trim() != modelName ||
            modelsDir.listFiles { file -> file.name.startsWith("encoder") && file.extension == "onnx" }.isNullOrEmpty() ||
            !file("$modelsDir/tokens.txt").exists()
    }
    
    doFirst {
        if (modelMarker.exists() && modelMarker.readText().trim() != modelName) {
            modelsDir.deleteRecursively()
        }
        modelsDir.mkdirs()
    }
    
    // 使用 curl 下载模型
    commandLine("curl", "-L", "-o", modelArchive.absolutePath, modelUrl)
    
    doLast {
        // 解压 tar.bz2（需要 tar 命令）
        exec {
            commandLine("tar", "-xjf", modelArchive.absolutePath, "-C", modelsDir.absolutePath, "--strip-components=1")
        }
        modelMarker.writeText("$modelName\n")
        println("✓ Sherpa-onnx model downloaded to $modelsDir")
    }
}

// 在 mergeDebugAssets 之前执行下载任务
tasks.named("mergeDebugAssets").configure {
    dependsOn("downloadSherpaModels")
}

tasks.named("mergeReleaseAssets").configure {
    dependsOn("downloadSherpaModels")
}
package com.moqi.im.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

object ModelManager {
    
    private const val TAG = "ModelManager"
    private const val MODEL_BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    
    // 使用较小的中文流式 Zipformer 模型
    private const val MODEL_ZIP = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2"
    
    data class ModelFile(
        val name: String,
        val url: String,
        val size: Long
    )
    
    fun getModelDir(context: Context): File {
        return File(context.getExternalFilesDir(null), "sherpa-models").apply {
            if (!exists()) mkdirs()
        }
    }
    
    fun isModelReady(context: Context): Boolean {
        val modelDir = getModelDir(context)
        return modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true
    }
    
    suspend fun downloadModel(context: Context, onProgress: (Float) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        try {
            val modelDir = getModelDir(context)
            if (isModelReady(context)) {
                return@withContext Result.success(modelDir.absolutePath)
            }
            
            onProgress(0.1f)
            
            // 下载模型文件
            val url = URL("$MODEL_BASE_URL$MODEL_ZIP")
            val connection = url.openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            val totalSize = connection.contentLength
            var downloaded = 0
            
            connection.getInputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    downloaded += bytesRead
                    if (totalSize > 0) {
                        onProgress(0.1f + 0.8f * downloaded / totalSize)
                    }
                }
            }
            
            onProgress(0.9f)
            
            // 模型下载后可能需要解压，这里简化处理
            // 实际使用时需要下载并解压 tar.bz2 文件
            
            onProgress(1.0f)
            Result.success(modelDir.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "下载模型失败", e)
            Result.failure(e)
        }
    }
}
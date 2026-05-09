package com.moqi.im.engine

import android.content.Context
import com.moqi.im.dict.Dictionary

/**
 * 精简版不包含 Sherpa-onnx；占位实现以满足编译与类型引用。
 */
class SherpaVoiceEngine(context: Context) : InputEngine {

    fun startListening(
        onResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: () -> Unit
    ) {
        onError()
    }

    fun stopListening() {}

    fun destroy() {}

    override fun processInput(input: String): List<String> = emptyList()
    override fun reset() {}
    override fun setDictionary(dict: Dictionary) {}
}

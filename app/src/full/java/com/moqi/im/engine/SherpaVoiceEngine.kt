package com.moqi.im.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.moqi.im.dict.Dictionary

/**
 * Sherpa-onnx 本地语音输入引擎。
 */
class SherpaVoiceEngine(private val context: Context) : InputEngine {

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var onResultCallback: ((String) -> Unit)? = null
    private var onFinalResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: (() -> Unit)? = null

    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast((0.1 * sampleRate).toInt())

    fun startListening(
        onResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: () -> Unit
    ) {
        onResultCallback = onResult
        onFinalResultCallback = onFinalResult
        onErrorCallback = onError

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError()
            return
        }

        try {
            startSherpaOnnxRecognition()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Sherpa voice recognition", e)
            onError()
        }
    }

    private fun startSherpaOnnxRecognition() {
        stopListening()

        val currentRecognizer = recognizer ?: createRecognizer().also {
            recognizer = it
        }
        val currentStream = currentRecognizer.createStream().also {
            stream = it
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        val recorder = audioRecord
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            currentStream.release()
            stream = null
            throw IllegalStateException("AudioRecord is not initialized")
        }

        isRecording = true
        recorder.startRecording()
        recordingThread = Thread({
            runRecognitionLoop(currentRecognizer, currentStream, recorder)
        }, "SherpaVoiceRecognition").also { it.start() }
    }

    private fun createRecognizer(): OnlineRecognizer {
        val modelDir = "models/sherpa"
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "$modelDir/decoder-epoch-99-avg-1.onnx",
                    joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer",
            ),
            enableEndpoint = true,
        )
        return OnlineRecognizer(assetManager = context.assets, config = config)
    }

    private fun runRecognitionLoop(
        recognizer: OnlineRecognizer,
        stream: OnlineStream,
        recorder: AudioRecord
    ) {
        val buffer = ShortArray(bufferSize)
        var lastText = ""

        try {
            while (isRecording) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val samples = FloatArray(read) { index ->
                    buffer[index] / 32768.0f
                }
                stream.acceptWaveform(samples, sampleRate)

                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                }

                val text = recognizer.getResult(stream).text
                if (text.isNotBlank() && text != lastText) {
                    lastText = text
                    mainHandler.post { onResultCallback?.invoke(text) }
                }

                if (recognizer.isEndpoint(stream)) {
                    if (lastText.isNotBlank()) {
                        val finalText = lastText
                        mainHandler.post { onFinalResultCallback?.invoke(finalText) }
                    }
                    recognizer.reset(stream)
                    lastText = ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sherpa recognition loop failed", e)
            mainHandler.post { onErrorCallback?.invoke() }
        }
    }

    fun stopListening() {
        isRecording = false
        recordingThread?.join(500)
        recordingThread = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        stream?.release()
        stream = null
    }

    fun destroy() {
        stopListening()
        recognizer?.release()
        recognizer = null
    }

    override fun processInput(input: String): List<String> = emptyList()
    override fun reset() {}
    override fun setDictionary(dict: Dictionary) {}

    private companion object {
        const val TAG = "SherpaVoiceEngine"
    }
}

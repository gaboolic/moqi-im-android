package com.moqi.im.core

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import com.moqi.im.engine.InputMode
import com.moqi.im.engine.MoqiImeEngineRunner
import com.moqi.im.engine.MoqiImeKeyMapper
import com.moqi.im.engine.MoqiImeResult
import com.moqi.im.engine.SherpaVoiceEngine
import com.moqi.im.keyboard.CandidateView
import com.moqi.im.keyboard.ComposeView
import com.moqi.im.keyboard.KeyCode
import com.moqi.im.keyboard.KeyboardMenuView
import com.moqi.im.keyboard.KeyboardView
import com.moqi.im.voice.ModelManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import mobilebridge.Mobilebridge

class MoqiInputMethodService : InputMethodService() {
    companion object {
        private const val TAG = "MoqiInputMethodService"
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        outInsets?.let {
            it.contentTopInsets = 0
            it.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
            it.touchableRegion.setEmpty()
        }
    }

    override fun onConfigureWindow(win: android.view.Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        val screenHeight = resources.displayMetrics.heightPixels
        val imeHeight = (screenHeight * 0.35).toInt()
        view.layoutParams?.height = imeHeight
        val inputArea = window?.window?.decorView?.findViewById<FrameLayout>(android.R.id.inputArea)
        inputArea?.layoutParams?.height = imeHeight
    }

    private var currentMode: InputMode = InputMode.PINYIN
    private lateinit var engineRunner: MoqiImeEngineRunner
    private var composingText: StringBuilder = StringBuilder()
    private var currentSchemaId: String = ""

    private var keyboardView: KeyboardView? = null
    private var keyboardMenuView: KeyboardMenuView? = null
    private var candidateView: CandidateView? = null
    private var composeView: ComposeView? = null
    private var imeView: View? = null

    private var shiftActive: Boolean = false
    private var shiftLocked: Boolean = false
    private var isT9Mode: Boolean = false
    private var modeBeforeVoice: InputMode = InputMode.PINYIN
    private var sherpaVoiceEngine: SherpaVoiceEngine? = null
    private var isListening: Boolean = false
    private var isSpaceVoiceHoldActive: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val downloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var t9TapCount: Int = 0
    private var t9CurrentKey: Int = 0
    private var t9Runnable: Runnable? = null
    private val T9_TIMEOUT: Long = 800L

    private val t9KeyMap = mapOf(
        KeyCode.T9_1 to "1.,?!",
        KeyCode.T9_2 to "2abc",
        KeyCode.T9_3 to "3def",
        KeyCode.T9_4 to "4ghi",
        KeyCode.T9_5 to "5jkl",
        KeyCode.T9_6 to "6mno",
        KeyCode.T9_7 to "7pqrs",
        KeyCode.T9_8 to "8tuv",
        KeyCode.T9_9 to "9wxyz",
        KeyCode.T9_0 to "0 ",
        KeyCode.T9_STAR to "*",
        KeyCode.T9_POUND to "#"
    )

    override fun onCreate() {
        super.onCreate()
        loadInputModePreference()
        engineRunner = MoqiImeEngineRunner(
            androidDataDir = applicationContext.filesDir.absolutePath,
            initialGuid = guidForMode(currentMode)
        )
        refreshCurrentSchema()
    }

    override fun onCreateInputView(): View {
        imeView = layoutInflater.inflate(com.moqi.im.R.layout.ime_view, null)
        keyboardView = imeView?.findViewById(com.moqi.im.R.id.keyboard_view)
        keyboardMenuView = imeView?.findViewById(com.moqi.im.R.id.keyboard_menu_view)
        candidateView = imeView?.findViewById(com.moqi.im.R.id.candidate_view)
        composeView = imeView?.findViewById(com.moqi.im.R.id.compose_view)

        keyboardView?.setOnKeyListener { keyCode, isShifted, swipeText ->
            if (swipeText != null) {
                handleSwipeText(swipeText)
            } else {
                handleKey(keyCode, isShifted)
            }
        }
        keyboardView?.setOnSpaceLongPressListener { pressed ->
            if (pressed) {
                startSpaceVoiceHold()
            } else {
                stopSpaceVoiceHold()
            }
        }
        keyboardMenuView?.callback = object : KeyboardMenuView.Callback {
            override fun onBack() = hideMenuPanel()
            override fun onCommand(commandId: Int) {
                engineRunner.command(commandId) { result ->
                    applyMoqiResult(result.result)
                    refreshMenuPanel()
                }
            }
            override fun onSchemeSet(name: String) {
                engineRunner.selectSchemeSet(name) { result, schemaId ->
                    if (!result.result.success) {
                        applyMoqiResult(result.result)
                        return@selectSchemeSet
                    }
                    applySchemaLayout(schemaId)
                    refreshMenuPanel()
                }
            }
            override fun onSchema(schemaId: String) {
                engineRunner.selectSchema(schemaId) { result, current ->
                    if (!result.result.success) {
                        applyMoqiResult(result.result)
                        return@selectSchema
                    }
                    applySchemaLayout(current.ifBlank { schemaId })
                    refreshMenuPanel()
                }
            }
            override fun onInputMethodPicker() = showInputMethodPicker()
            override fun onVoiceInput() {
                hideMenuPanel()
                enterVoiceMode()
            }
            override fun onOpenSettings() = launchSettings()
            override fun onDownloadScheme(url: String, schemeSetName: String) {
                downloadSchemeSet(url, schemeSetName)
            }
        }

        candidateView?.setOnCandidateIndexSelectedListener { index ->
            engineRunner.selectCandidate(index) { engineResult ->
                applyMoqiResult(engineResult.result)
            }
        }

        updateKeyboard()
        return imeView!!
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        clearTextEngineState()
        updateUI()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        updateKeyboard()
    }

    private fun handleKey(keyCode: Int, isShifted: Boolean) {
        when (keyCode) {
            KeyCode.DELETE -> handleBackspace()
            KeyCode.ENTER -> handleEnter()
            KeyCode.SPACE -> handleSpace()
            KeyCode.SHIFT -> handleShift()
            KeyCode.MODE_SWITCH -> cycleInputMode()
            KeyCode.VOICE -> {
                if (currentMode == InputMode.VOICE && isListening) {
                    stopVoiceListening()
                    composeView?.setComposingText("")
                } else if (currentMode == InputMode.VOICE) {
                    startVoiceListening()
                } else {
                    enterVoiceMode()
                }
            }
            KeyCode.MENU -> showMenuPanel()
            KeyCode.RETYPE -> clearTextEngineState()
            KeyCode.SYMBOL_LAYOUT -> keyboardView?.setLayout(KeyboardView.Layout.SYMBOL)
            KeyCode.NUMBER_LAYOUT -> keyboardView?.setLayout(KeyboardView.Layout.NUMBER)
            KeyCode.RETURN_TO_TEXT -> updateKeyboard()
            KeyCode.TEXT_DOT_COM -> commitText(".com")
            KeyCode.EXIT_VOICE -> exitVoiceMode()
            KeyCode.COMMA -> {
                submitMoqiKey(','.code, ','.code, fallbackOnSuccessOnly = true) {
                    commitText("，")
                }
            }
            KeyCode.PERIOD -> {
                submitMoqiKey(MoqiImeKeyMapper.VK_OEM_PERIOD, '.'.code, fallbackOnSuccessOnly = true) {
                    commitText("。")
                }
            }
            KeyCode.SWITCH_TO_QWERTY -> {
                switchRimeSchemaForLayout(t9 = false)
            }
            KeyCode.SWITCH_TO_T9 -> {
                switchRimeSchemaForLayout(t9 = true)
            }
            in KeyCode.T9_POUND..KeyCode.T9_1 -> handleT9Key(keyCode)
            else -> {
                val mapped = MoqiImeKeyMapper.fromAndroidKeyCode(keyCode, isShifted || shiftActive)
                if (mapped != null) {
                    handleCharacter(mapped.first, mapped.second)
                } else if (keyCode >= 0x20) {
                    commitText(keyCode.toChar().toString())
                }
            }
        }
    }

    private fun handleSwipeText(text: String) {
        if (text.isBlank()) return
        if (currentMode == InputMode.ENGLISH) {
            commitText(text)
            return
        }
        if (text.length == 1) {
            val ch = text[0]
            submitMoqiKey(ch.code, ch.code, fallbackOnSuccessOnly = true) {
                commitText(text)
            }
        } else {
            commitText(text)
        }
    }

    private fun handleT9Key(keyCode: Int) {
        val chars = t9KeyMap[keyCode] ?: return

        if (currentMode == InputMode.ENGLISH || currentMode == InputMode.PINYIN) {
            if (isT9Mode && currentMode == InputMode.PINYIN) {
                handleT9Pinyin(keyCode)
                return
            }
        }

        t9Runnable?.let { handler.removeCallbacks(it) }

        if (t9CurrentKey == keyCode && t9TapCount < chars.length - 1) {
            t9TapCount++
        } else {
            if (t9CurrentKey != 0 && t9TapCount > 0) {
                val prevChars = t9KeyMap[t9CurrentKey]
                if (prevChars != null && t9TapCount < prevChars.length) {
                    commitText(prevChars[t9TapCount].toString())
                }
            }
            t9TapCount = 0
            t9CurrentKey = keyCode
        }

        t9Runnable = Runnable {
            val currentChars = t9KeyMap[t9CurrentKey]
            if (currentChars != null && t9TapCount < currentChars.length) {
                commitText(currentChars[t9TapCount].toString())
            }
            resetT9State()
        }
        handler.postDelayed(t9Runnable!!, T9_TIMEOUT)
    }

    private fun handleT9Pinyin(keyCode: Int) {
        val digit = when (keyCode) {
            KeyCode.T9_2 -> '2'
            KeyCode.T9_3 -> '3'
            KeyCode.T9_4 -> '4'
            KeyCode.T9_5 -> '5'
            KeyCode.T9_6 -> '6'
            KeyCode.T9_7 -> '7'
            KeyCode.T9_8 -> '8'
            KeyCode.T9_9 -> '9'
            else -> return
        }
        submitMoqiKey(digit.code, digit.code) {
            if (currentMode == InputMode.ENGLISH) {
                commitText(digit.toString())
            }
        }
    }

    private fun resetT9State() {
        t9TapCount = 0
        t9CurrentKey = 0
        t9Runnable = null
    }

    private fun commitText(text: String) {
        currentInputConnection.commitText(text, 1)
    }

    private fun handleCharacter(keyCode: Int, charCode: Int) {
        if (currentMode == InputMode.ENGLISH) {
            commitText(charCode.toChar().toString())
        } else {
            submitMoqiKey(keyCode, charCode, fallbackOnSuccessOnly = true) {
                commitText(charCode.toChar().toString())
            }
        }
        if (shiftActive && !shiftLocked) {
            shiftActive = false
            keyboardView?.setShifted(false)
        }
    }

    private fun handleBackspace() {
        submitMoqiKey(MoqiImeKeyMapper.VK_BACK, fallbackOnFailure = true) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    private fun handleEnter() {
        submitMoqiKey(MoqiImeKeyMapper.VK_RETURN, fallbackOnFailure = true) {
            sendKeyChar('\n')
        }
    }

    private fun handleSpace() {
        submitMoqiKey(MoqiImeKeyMapper.VK_SPACE, ' '.code, fallbackOnFailure = true) {
            sendKeyChar(' ')
        }
    }

    private fun handleShift() {
        if (shiftActive && !shiftLocked) {
            shiftActive = false
            shiftLocked = true
        } else if (shiftLocked) {
            shiftActive = false
            shiftLocked = false
        } else {
            shiftActive = true
        }
        keyboardView?.setShifted(shiftActive || shiftLocked)
    }

    private fun cycleInputMode() {
        val textModes = listOf(InputMode.PINYIN, InputMode.WUBI, InputMode.ENGLISH)
        val currentTextMode = if (currentMode == InputMode.VOICE) modeBeforeVoice else currentMode
        val nextIndex = (textModes.indexOf(currentTextMode) + 1) % textModes.size
        switchMode(textModes[nextIndex])
    }

    private fun enterVoiceMode() {
        modeBeforeVoice = if (currentMode == InputMode.VOICE) modeBeforeVoice else currentMode
        switchMode(InputMode.VOICE)
        startVoiceListening()
    }

    private fun startSpaceVoiceHold() {
        if (isListening) return
        modeBeforeVoice = if (currentMode == InputMode.VOICE) modeBeforeVoice else currentMode
        isSpaceVoiceHoldActive = true
        startVoiceListening()
    }

    private fun stopSpaceVoiceHold() {
        if (!isSpaceVoiceHoldActive) return
        isSpaceVoiceHoldActive = false
        val text = composingText.toString().trim()
        if (text.isNotBlank()) {
            currentInputConnection.commitText(text, 1)
        }
        stopVoiceListening()
        composingText.clear()
        updateComposeView()
    }

    @SuppressLint("NewApi")
    private fun startVoiceListening() {
        val voiceHoldRequest = isSpaceVoiceHoldActive
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRecordAudioPermission(voiceHoldRequest)
            return
        }

        // 检查模型是否已下载（Sherpa-onnx 需要手动下载模型）
        if (!ModelManager.isModelReady(this)) {
            composeView?.setComposingText("语音模型未下载")
            handler.postDelayed({
                if (voiceHoldRequest) {
                    stopSpaceVoiceHold()
                } else {
                    exitVoiceMode()
                }
            }, 2000)
            return
        }

        // 初始化 SherpaVoiceEngine
        if (sherpaVoiceEngine == null) {
            sherpaVoiceEngine = SherpaVoiceEngine(this)
        }

        isListening = true
        composeView?.setComposingText("正在聆听...")
        
        sherpaVoiceEngine?.startListening(
            onResult = { text ->
                composingText.clear()
                composingText.append(text)
                updateComposeView()
            },
            onFinalResult = { text ->
                currentInputConnection.commitText(text, 1)
                composingText.clear()
                composeView?.setComposingText("正在聆听...")
            },
            onError = {
                isListening = false
                composeView?.setComposingText("语音识别失败")
                handler.postDelayed({
                    if (voiceHoldRequest) {
                        stopSpaceVoiceHold()
                    } else {
                        exitVoiceMode()
                    }
                }, 1500)
            }
        )
    }

    @SuppressLint("NewApi")
    private fun requestRecordAudioPermission(voiceHoldRequest: Boolean = false) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        composeView?.setComposingText("请授权麦克风权限后重试")
        handler.postDelayed({
            if (voiceHoldRequest) {
                stopSpaceVoiceHold()
            } else {
                exitVoiceMode()
            }
        }, 2000)
    }

    private fun stopVoiceListening() {
        sherpaVoiceEngine?.stopListening()
        sherpaVoiceEngine?.destroy()
        sherpaVoiceEngine = null
        isListening = false
    }

    private fun exitVoiceMode() {
        isSpaceVoiceHoldActive = false
        stopVoiceListening()
        currentMode = modeBeforeVoice
        resetTextEngine()
        updateKeyboard()
    }

    private fun switchMode(mode: InputMode) {
        if (currentMode == InputMode.VOICE) stopVoiceListening()
        currentMode = mode
        resetTextEngine()
        updateKeyboard()
    }

    private fun resetTextEngine() {
        if (::engineRunner.isInitialized) {
            engineRunner.resetSession(guidForMode(currentMode)) { schemaId ->
                applySchemaLayout(schemaId)
            }
        }
        composingText.clear()
        candidateView?.setCandidates(emptyList())
        updateComposeView()
    }

    private fun clearTextEngineState() {
        if (::engineRunner.isInitialized) {
            engineRunner.resetComposition()
        }
        composingText.clear()
        candidateView?.setCandidates(emptyList())
        updateComposeView()
    }

    private fun guidForMode(mode: InputMode): String {
        return when (mode) {
            InputMode.PINYIN, InputMode.WUBI -> Mobilebridge.GUIDRime
            else -> Mobilebridge.GUIDMoqi
        }
    }

    private fun applyMoqiResult(result: MoqiImeResult): Boolean {
        if (!result.success) {
            Log.w(TAG, "moqi-ime result failed: ${result.error}")
            if (result.error.isNotBlank()) {
                composeView?.setComposingText(result.error)
            }
            return false
        }

        if (result.commit.isNotBlank()) {
            currentInputConnection.commitText(result.commit, 1)
        }

        composingText.clear()
        composingText.append(result.composition)
        updateComposeView()

        if (result.showCandidates) {
            candidateView?.setCandidateEntries(result.candidateEntries)
        } else {
            updateCandidates(emptyList())
        }

        return result.handled
    }

    private fun submitMoqiKey(
        keyCode: Int,
        charCode: Int = 0,
        fallbackOnSuccessOnly: Boolean = false,
        fallbackOnFailure: Boolean = false,
        fallback: (() -> Unit)? = null
    ) {
        engineRunner.keyDown(keyCode, charCode) { engineResult ->
            val result = engineResult.result
            Log.d(TAG, "engineResult seq=${engineResult.sequence} mode=$currentMode keyCode=$keyCode charCode=$charCode success=${result.success} handled=${result.handled} composition=${result.composition} commit=${result.commit} candidates=${result.candidates.size} error=${result.error}")
            val handled = applyMoqiResult(result)
            val shouldFallback = fallback != null && !handled &&
                ((fallbackOnSuccessOnly && result.success) || fallbackOnFailure || (!fallbackOnSuccessOnly && result.success))
            if (shouldFallback) {
                fallback?.invoke()
            }
        }
    }

    private fun refreshCurrentSchema() {
        if (!::engineRunner.isInitialized) return
        engineRunner.currentSchemaId { schemaId ->
            applySchemaLayout(schemaId)
        }
    }

    private fun applySchemaLayout(schemaId: String) {
        currentSchemaId = schemaId
        isT9Mode = isT9Schema(schemaId)
        updateKeyboard()
    }

    private fun switchRimeSchemaForLayout(t9: Boolean) {
        if (currentMode == InputMode.VOICE) {
            currentMode = modeBeforeVoice
            resetTextEngine()
        }
        resetT9State()
        isT9Mode = t9
        updateKeyboard()
        if (currentMode == InputMode.ENGLISH) {
            return
        }
        val targetSchema = if (t9) "rime_frost_t9" else "rime_frost"
        currentSchemaId = targetSchema
        engineRunner.selectSchema(targetSchema) { engineResult, schemaId ->
            if (!engineResult.result.success) {
                applyMoqiResult(engineResult.result)
                return@selectSchema
            }
            applySchemaLayout(schemaId.ifBlank { targetSchema })
            resetTextEngine()
        }
    }

    private fun isT9Schema(schemaId: String): Boolean {
        return schemaId.contains("_t9", ignoreCase = true) || schemaId.equals("rime_frost_t9", ignoreCase = true)
    }

    private fun updateUI() {
        updateComposeView()
        updateCandidates(emptyList())
    }

    private fun updateCandidates(candidates: List<String>) {
        candidateView?.setCandidates(candidates)
    }

    private fun updateComposeView() {
        composeView?.setComposingText(composingText.toString())
    }

    private fun updateKeyboard() {
        val layout = if (currentMode == InputMode.VOICE) {
            KeyboardView.Layout.VOICE
        } else if (isT9Mode) {
            when (currentMode) {
                InputMode.ENGLISH -> KeyboardView.Layout.T9_EN
                else -> KeyboardView.Layout.T9_CN
            }
        } else {
            when (currentMode) {
                InputMode.ENGLISH -> KeyboardView.Layout.QWERTY_EN
                else -> KeyboardView.Layout.QWERTY_CN
            }
        }
        keyboardView?.setLayout(layout)
    }

    private fun showMenuPanel() {
        keyboardView?.visibility = View.GONE
        keyboardMenuView?.visibility = View.VISIBLE
        refreshMenuPanel()
    }

    private fun hideMenuPanel() {
        keyboardMenuView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
        updateKeyboard()
    }

    private fun refreshMenuPanel() {
        if (keyboardMenuView?.visibility != View.VISIBLE) return
        engineRunner.menuState { menuEntries, schemeSets, currentSchemeSet, schemas, schemaId ->
            keyboardMenuView?.render(
                KeyboardMenuView.MenuState(
                    menuEntries = menuEntries,
                    schemeSets = schemeSets,
                    currentSchemeSet = currentSchemeSet,
                    schemas = schemas,
                    currentSchemaId = schemaId
                )
            )
        }
    }

    private fun showInputMethodPicker() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }

    private fun downloadSchemeSet(rawUrl: String, rawName: String) {
        val url = rawUrl.trim()
        val name = sanitizeSchemeSetName(rawName)
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            showMessage("只支持 http/https URL")
            return
        }
        if (name.isBlank()) {
            showMessage("请输入方案集名称")
            return
        }
        composeView?.setComposingText("正在下载方案集...")
        downloadExecutor.execute {
            val result = runCatching {
                val root = File(applicationContext.filesDir, "Moqi").apply { mkdirs() }
                val target = File(root, name).canonicalFile
                if (!target.path.startsWith(root.canonicalPath + File.separator)) {
                    error("方案集名称不合法")
                }
                if (target.exists()) {
                    target.deleteRecursively()
                }
                target.mkdirs()
                downloadAndUnzip(url, target)
                name
            }
            handler.post {
                result.onSuccess { schemeSet ->
                    showMessage("方案集下载完成: $schemeSet")
                    engineRunner.selectSchemeSet(schemeSet) { engineResult, schemaId ->
                        if (!engineResult.result.success) {
                            applyMoqiResult(engineResult.result)
                            return@selectSchemeSet
                        }
                        applySchemaLayout(schemaId)
                        refreshMenuPanel()
                    }
                }.onFailure { error ->
                    showMessage("下载失败: ${error.message.orEmpty().ifBlank { error::class.java.simpleName }}")
                }
            }
        }
    }

    private fun downloadAndUnzip(rawUrl: String, target: File) {
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        connection.inputStream.use { stream ->
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val output = File(target, entry.name).canonicalFile
                    if (!output.path.startsWith(target.canonicalPath + File.separator)) {
                        error("ZIP 路径不安全: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        output.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun sanitizeSchemeSetName(name: String): String {
        return name.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_").trim('_', '.', '-')
    }

    private fun showMessage(message: String) {
        composeView?.setComposingText(message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun loadInputModePreference() {
        val prefs = getSharedPreferences("moqi_im_prefs", MODE_PRIVATE)
        val modeStr = prefs.getString("input_mode", "pinyin") ?: "pinyin"
        currentMode = when (modeStr) {
            "wubi" -> InputMode.WUBI
            "english" -> InputMode.ENGLISH
            else -> InputMode.PINYIN
        }
    }

    private fun launchSettings() {
        val intent = Intent(this, com.moqi.im.settings.SettingsActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopVoiceListening()
        sherpaVoiceEngine?.destroy()
        sherpaVoiceEngine = null
        if (::engineRunner.isInitialized) {
            engineRunner.close()
        }
        downloadExecutor.shutdown()
        keyboardView = null
        keyboardMenuView = null
        candidateView = null
        composeView = null
        imeView = null
    }
}
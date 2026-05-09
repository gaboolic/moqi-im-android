package com.moqi.im.core

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import com.moqi.im.BuildConfig
import com.moqi.im.engine.CandidateEntry
import com.moqi.im.engine.CandidateEntrySource
import com.moqi.im.engine.InputMode
import com.moqi.im.engine.MoqiImeEngineRunner
import com.moqi.im.engine.MoqiImeKeyMapper
import com.moqi.im.engine.MoqiImeResult
import com.moqi.im.engine.RimeSchemaEntry
import com.moqi.im.engine.SherpaVoiceEngine
import com.moqi.im.keyboard.CandidateView
import com.moqi.im.keyboard.ComposeView
import com.moqi.im.keyboard.KeyCode
import com.moqi.im.keyboard.KeyboardMenuView
import com.moqi.im.keyboard.KeyboardView
import com.moqi.im.keyboard.T9Pinyin
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
        private const val RIME_INIT_MESSAGE = "正在初始化 Rime…"
        private const val KEY_VIBRATION_MS = 12L
        private const val INITIAL_EXPANDED_PREFETCH_PAGES = 5
        private const val CLIPBOARD_CANDIDATE_PREVIEW_MAX = 42
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        outInsets?.let {
            val panel = inputPanelView
            if (panel != null && panel.isShown) {
                panel.getLocationInWindow(inputPanelLocation)
                val panelTop = inputPanelLocation[1]
                it.contentTopInsets = panelTop
                it.visibleTopInsets = panelTop
            } else {
                it.contentTopInsets = 0
                it.visibleTopInsets = 0
            }
            it.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            it.touchableRegion.setEmpty()
        }
    }

    override fun onConfigureWindow(win: android.view.Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        val screenHeight = resources.displayMetrics.heightPixels
        val imeHeight = (screenHeight * 0.32).toInt()
        view.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        val inputArea = window?.window?.decorView?.findViewById<FrameLayout>(android.R.id.inputArea)
        inputArea?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        inputPanelView?.layoutParams?.let { params ->
            params.height = imeHeight
            inputPanelView?.layoutParams = params
        }
    }

    private var currentMode: InputMode = InputMode.PINYIN
    private var lastChineseMode: InputMode = InputMode.PINYIN
    private lateinit var engineRunner: MoqiImeEngineRunner
    private var composingText: StringBuilder = StringBuilder()
    private var currentSchemaId: String = ""

    private var keyboardView: KeyboardView? = null
    private var keyboardMenuView: KeyboardMenuView? = null
    private var candidateView: CandidateView? = null
    private var composeView: ComposeView? = null
    private var inputPanelView: View? = null
    private var imeView: View? = null
    private val inputPanelLocation = IntArray(2)

    private var shiftActive: Boolean = false
    private var shiftLocked: Boolean = false
    private var isT9Mode: Boolean = false
    private var modeBeforeVoice: InputMode = InputMode.PINYIN
    private var sherpaVoiceEngine: SherpaVoiceEngine? = null
    private var isListening: Boolean = false
    private var isSpaceVoiceHoldActive: Boolean = false
    private var isEngineInitializing: Boolean = true
    private var expandedCandidatePageIndex: Int = 0
    private var isExpandedCandidateLoading: Boolean = false
    private var expandedCandidateInitialPrefetchRemaining: Int = 0
    private var dismissedClipboardText: String? = null
    private var clipboardManager: ClipboardManager? = null
    private val clipboardChangeListener = ClipboardManager.OnPrimaryClipChangedListener {
        dismissedClipboardText = null
        if (composingText.isBlank()) {
            updateCandidates(emptyList())
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val downloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val powerManager: PowerManager by lazy {
        getSystemService(POWER_SERVICE) as PowerManager
    }
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }
    private var t9TapCount: Int = 0
    private var t9CurrentKey: Int = 0
    private var t9PinyinDigits: StringBuilder = StringBuilder()
    private var t9ActiveSegmentIndex: Int = 0
    private val t9SelectedPinyinBySegment = mutableMapOf<Int, String>()
    private val t9InferredPinyinBySegment = mutableMapOf<Int, String>()
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
    }

    private fun ensureEngineRunner() {
        if (::engineRunner.isInitialized) return
        isEngineInitializing = true
        showRimeInitializingIfNeeded()
        engineRunner = MoqiImeEngineRunner(
            androidDataDir = applicationContext.filesDir.absolutePath,
            initialGuid = guidForMode(currentMode)
        ) { schemaId ->
            isEngineInitializing = false
            applySchemaLayout(schemaId)
            clearRimeInitializingMessage()
        }
    }

    override fun onCreateInputView(): View {
        imeView = layoutInflater.inflate(com.moqi.im.R.layout.ime_view, null)
        keyboardView = imeView?.findViewById(com.moqi.im.R.id.keyboard_view)
        keyboardMenuView = imeView?.findViewById(com.moqi.im.R.id.keyboard_menu_view)
        candidateView = imeView?.findViewById(com.moqi.im.R.id.candidate_view)
        composeView = imeView?.findViewById(com.moqi.im.R.id.compose_view)
        inputPanelView = imeView?.findViewById(com.moqi.im.R.id.input_panel)

        keyboardView?.setOnKeyListener { keyCode, isShifted, swipeText ->
            if (keyCode == KeyCode.T9_PINYIN_OPTION && swipeText != null) {
                handleT9PinyinOption(swipeText)
            } else if (swipeText != null) {
                handleSwipeText(swipeText)
            } else {
                handleKey(keyCode, isShifted)
            }
        }
        keyboardView?.setOnSpaceLongPressListener { pressed ->
            if (!BuildConfig.VOICE_INPUT_ENABLED) return@setOnSpaceLongPressListener
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
                    val message = result.result.message.ifBlank { result.result.error }
                    if (message.isNotBlank()) {
                        showMessage(message)
                    }
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
                if (!BuildConfig.VOICE_INPUT_ENABLED) return
                hideMenuPanel()
                enterVoiceMode()
            }
            override fun onOpenSettings() = launchSettings()
            override fun onDownloadScheme(url: String) {
                downloadSchemeSet(url)
            }
        }

        candidateView?.setOnCandidateIndexSelectedListener { index ->
            val candidateView = candidateView ?: return@setOnCandidateIndexSelectedListener
            val entry = candidateView.getCandidateEntry(index)
            if (entry?.source == CandidateEntrySource.CLIPBOARD) {
                commitClipboardCandidate(entry)
                return@setOnCandidateIndexSelectedListener
            }
            if (!::engineRunner.isInitialized) {
                showRimeInitializingIfNeeded()
                return@setOnCandidateIndexSelectedListener
            }
            val rimeIndex = candidateView.rimeCandidateIndexFor(index)
            if (rimeIndex < 0) return@setOnCandidateIndexSelectedListener
            engineRunner.selectCandidate(rimeIndex) { engineResult ->
                applyMoqiResult(engineResult.result)
            }
        }
        candidateView?.setOnExpandedCandidateIndexSelectedListener { pageIndex, pageLocalIndex ->
            selectExpandedCandidate(pageIndex, pageLocalIndex)
        }
        candidateView?.setOnExpandedChangedListener { expanded ->
            setCandidateExpanded(expanded)
        }
        candidateView?.setOnExpandedLoadNextPageListener {
            loadNextExpandedCandidatePage()
        }
        candidateView?.setOnMenuClickListener {
            showMenuPanel()
        }
        candidateView?.setOnEmojiClickListener {
            keyboardView?.setLayout(KeyboardView.Layout.EMOJI)
        }
        candidateView?.setOnKeyboardDismissListener {
            requestHideSelf(0)
        }
        candidateView?.setOnClipboardDismissListener {
            dismissedClipboardText = currentClipboardText()
            updateCandidates(emptyList())
        }
        registerClipboardListener()

        updateKeyboard()
        showRimeInitializingIfNeeded()
        handler.post {
            ensureEngineRunner()
        }
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
        performKeyVibration()
        when (keyCode) {
            KeyCode.DELETE -> handleBackspace()
            KeyCode.ENTER -> handleEnter()
            KeyCode.SPACE -> handleSpace()
            KeyCode.SHIFT -> handleShift()
            KeyCode.MODE_SWITCH -> cycleInputMode()
            KeyCode.VOICE -> {
                if (!BuildConfig.VOICE_INPUT_ENABLED) {
                    // ignore
                } else if (currentMode == InputMode.VOICE && isListening) {
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
            KeyCode.EMOJI_LAYOUT -> keyboardView?.setLayout(KeyboardView.Layout.EMOJI)
            KeyCode.RETURN_TO_TEXT -> updateKeyboard()
            KeyCode.TEXT_DOT_COM -> commitText(".com")
            KeyCode.EXIT_VOICE -> exitVoiceMode()
            KeyCode.COMMA -> {
                if (currentMode == InputMode.ENGLISH) {
                    commitText(",")
                } else {
                    submitMoqiKey(','.code, ','.code, fallbackOnSuccessOnly = true) {
                        commitText("，")
                    }
                }
            }
            KeyCode.PERIOD -> {
                if (currentMode == InputMode.ENGLISH) {
                    commitText(".")
                } else {
                    submitMoqiKey(MoqiImeKeyMapper.VK_OEM_PERIOD, '.'.code, fallbackOnSuccessOnly = true) {
                        commitText("。")
                    }
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
        performKeyVibration()
        val normalizedText = normalizeSwipeTextForMode(text)
        if (keyboardView?.isDirectCommitLayout() == true) {
            commitText(normalizedText)
            return
        }
        if (isT9Mode && normalizedText.length == 1 && normalizedText[0].isDigit()) {
            commitText(normalizedText)
            return
        }
        if (currentMode == InputMode.ENGLISH) {
            if (isPairedSymbol(normalizedText)) {
                commitPairedText(normalizedText)
                return
            }
            commitText(normalizedText)
            return
        }
        if (isPairedSymbol(normalizedText)) {
            commitPairedText(normalizedText)
            return
        }
        if (normalizedText.length == 1) {
            val ch = normalizedText[0]
            submitMoqiKey(ch.code, ch.code, fallbackOnSuccessOnly = true) {
                commitText(normalizedText)
            }
        } else {
            commitText(normalizedText)
        }
    }

    private fun isPairedSymbol(text: String): Boolean {
        return text == "“”" || text == "（）" || text == "\"\"" || text == "()"
    }

    private fun normalizeSwipeTextForMode(text: String): String {
        return if (currentMode == InputMode.ENGLISH) {
            when (text) {
                "“”" -> "\"\""
                "（）" -> "()"
                "，" -> ","
                "。" -> "."
                "：" -> ":"
                "；" -> ";"
                else -> text
            }
        } else {
            when (text) {
                "\"\"" -> "“”"
                "()" -> "（）"
                ":" -> "："
                ";" -> "；"
                else -> text
            }
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
            KeyCode.T9_1 -> '1'
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
        if (digit == '1' && (t9PinyinDigits.isEmpty() || t9PinyinDigits.last() == '1')) {
            return
        }
        val previousWasSeparator = t9PinyinDigits.lastOrNull() == '1'
        t9PinyinDigits.append(digit)
        val segmentLastIndex = t9Segments().lastIndex.coerceAtLeast(0)
        t9ActiveSegmentIndex = when {
            digit == '1' || previousWasSeparator -> segmentLastIndex
            else -> t9ActiveSegmentIndex.coerceIn(0, segmentLastIndex)
        }
        t9SelectedPinyinBySegment.keys
            .filter { it >= t9Segments().size }
            .forEach { t9SelectedPinyinBySegment.remove(it) }
        t9InferredPinyinBySegment.keys
            .filter { it >= t9Segments().size }
            .forEach { t9InferredPinyinBySegment.remove(it) }
        if (digit == '1') {
            t9InferredPinyinBySegment.clear()
        }
        updateT9PinyinOptions()
        val engineChar = digit
        submitMoqiKey(engineChar.code, engineChar.code) {
            if (currentMode == InputMode.ENGLISH) {
                commitText(engineChar.toString())
            }
        }
    }

    private fun handleT9PinyinOption(pinyin: String) {
        if (!::engineRunner.isInitialized || pinyin.isBlank()) return
        val segments = t9Segments()
        if (segments.isEmpty()) return
        val segmentIndex = t9ActiveSegmentIndex.coerceIn(0, segments.lastIndex)
        splitT9SegmentForSelectedPinyin(segmentIndex, pinyin, segments)
        t9SelectedPinyinBySegment[segmentIndex] = pinyin
        val updatedSegments = t9Segments()
        t9ActiveSegmentIndex = (segmentIndex + 1).coerceAtMost(updatedSegments.lastIndex)
        updateT9PinyinOptions()
        replayT9DisplayComposition()
    }

    private fun replayT9DisplayComposition() {
        val replayText = t9DisplayComposition()
        replayTextToEngine(replayText)
    }

    private fun replayTextToEngine(replayText: String) {
        engineRunner.resetComposition {
            composingText.clear()
            updateCandidateEntries(emptyList())
            replayText.forEach { ch ->
                submitMoqiKey(ch.code, ch.code)
            }
        }
    }

    private fun splitT9SegmentForSelectedPinyin(segmentIndex: Int, pinyin: String, segments: List<String>) {
        val selectedDigits = T9Pinyin.digitsFor(pinyin)
        val segmentDigits = segments.getOrNull(segmentIndex) ?: return
        if (selectedDigits.isBlank() ||
            selectedDigits.length >= segmentDigits.length ||
            !segmentDigits.startsWith(selectedDigits)
        ) {
            return
        }

        val updatedSegments = segments.toMutableList()
        updatedSegments[segmentIndex] = selectedDigits
        updatedSegments.add(segmentIndex + 1, segmentDigits.drop(selectedDigits.length))
        t9PinyinDigits.clear()
        t9PinyinDigits.append(updatedSegments.joinToString("1"))

        val oldSelected = t9SelectedPinyinBySegment.toMap()
        t9SelectedPinyinBySegment.clear()
        oldSelected.forEach { (index, selected) ->
            when {
                index < segmentIndex -> t9SelectedPinyinBySegment[index] = selected
                index > segmentIndex -> t9SelectedPinyinBySegment[index + 1] = selected
            }
        }
        t9InferredPinyinBySegment.clear()
    }

    private fun updateT9PinyinOptions() {
        val segments = t9Segments()
        t9SelectedPinyinBySegment.keys
            .filter { it >= segments.size }
            .forEach { t9SelectedPinyinBySegment.remove(it) }
        t9InferredPinyinBySegment.keys
            .filter { it >= segments.size }
            .forEach { t9InferredPinyinBySegment.remove(it) }
        val options = if (isT9Mode && currentMode == InputMode.PINYIN && segments.isNotEmpty()) {
            t9ActiveSegmentIndex = t9ActiveSegmentIndex.coerceIn(0, segments.lastIndex)
            T9Pinyin.optionsFor(segments[t9ActiveSegmentIndex])
        } else {
            emptyList()
        }
        keyboardView?.setT9PinyinOptions(options)
        updateComposeView()
    }

    private fun resetT9State() {
        t9TapCount = 0
        t9CurrentKey = 0
        t9PinyinDigits.clear()
        t9SelectedPinyinBySegment.clear()
        t9InferredPinyinBySegment.clear()
        t9ActiveSegmentIndex = 0
        updateT9PinyinOptions()
        t9Runnable = null
    }

    private fun commitText(text: String) {
        consumeClipboardSuggestion()
        currentInputConnection.commitText(text, 1)
    }

    private fun commitPairedText(text: String) {
        consumeClipboardSuggestion()
        currentInputConnection.commitText(text, 1)
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
    }

    private fun handleCharacter(keyCode: Int, charCode: Int) {
        if (currentMode == InputMode.ENGLISH) {
            commitText(charCode.toChar().toString())
        } else {
            submitMoqiKey(keyCode, charCode, retryFullPinyinOnUnhandled = true)
        }
        if (shiftActive && !shiftLocked) {
            shiftActive = false
            updateShiftKeyState()
        }
    }

    private fun handleBackspace() {
        if (isT9Mode && currentMode == InputMode.PINYIN && t9PinyinDigits.isNotEmpty()) {
            t9PinyinDigits.deleteAt(t9PinyinDigits.lastIndex)
            updateT9PinyinOptions()
        }
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
        updateShiftKeyState()
    }

    private fun cycleInputMode() {
        val currentTextMode = if (currentMode == InputMode.VOICE) modeBeforeVoice else currentMode
        val targetMode = if (currentTextMode == InputMode.ENGLISH) {
            lastChineseMode
        } else {
            if (currentTextMode == InputMode.PINYIN || currentTextMode == InputMode.WUBI) {
                lastChineseMode = currentTextMode
            }
            InputMode.ENGLISH
        }
        switchMode(targetMode)
    }

    private fun enterVoiceMode() {
        if (!BuildConfig.VOICE_INPUT_ENABLED) return
        modeBeforeVoice = if (currentMode == InputMode.VOICE) modeBeforeVoice else currentMode
        switchMode(InputMode.VOICE)
        startVoiceListening()
    }

    private fun startSpaceVoiceHold() {
        if (!BuildConfig.VOICE_INPUT_ENABLED) return
        if (isListening) return
        performKeyVibration()
        modeBeforeVoice = if (currentMode == InputMode.VOICE) modeBeforeVoice else currentMode
        isSpaceVoiceHoldActive = true
        startVoiceListening()
    }

    private fun stopSpaceVoiceHold() {
        if (!isSpaceVoiceHoldActive) return
        isSpaceVoiceHoldActive = false
        val text = composingText.toString().trim()
        if (text.isNotBlank()) {
            consumeClipboardSuggestion()
            currentInputConnection.commitText(text, 1)
        }
        stopVoiceListening()
        composingText.clear()
        updateComposeView()
    }

    @SuppressLint("NewApi")
    private fun startVoiceListening() {
        if (!BuildConfig.VOICE_INPUT_ENABLED) return
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
                consumeClipboardSuggestion()
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
        if (mode == InputMode.PINYIN || mode == InputMode.WUBI) {
            lastChineseMode = mode
        }
        currentMode = mode
        shiftActive = false
        shiftLocked = false
        resetTextEngine()
        updateKeyboard()
    }

    private fun resetTextEngine() {
        if (::engineRunner.isInitialized) {
            isEngineInitializing = true
            showRimeInitializingIfNeeded()
            engineRunner.resetSession(guidForMode(currentMode)) { schemaId ->
                isEngineInitializing = false
                applySchemaLayout(schemaId)
                clearRimeInitializingMessage()
            }
        }
        resetT9State()
        composingText.clear()
            updateCandidateEntries(emptyList())
        updateComposeView()
        showRimeInitializingIfNeeded()
    }

    private fun clearTextEngineState() {
        if (::engineRunner.isInitialized) {
            engineRunner.resetComposition()
        }
        resetT9State()
        composingText.clear()
            updateCandidateEntries(emptyList())
        updateComposeView()
        showRimeInitializingIfNeeded()
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
            consumeClipboardSuggestion()
            currentInputConnection.commitText(result.commit, 1)
            t9PinyinDigits.clear()
            updateT9PinyinOptions()
        }

        updateT9InferredPinyin(result.candidateEntries)
        val displayComposition = t9DisplayComposition().takeIf { it.isNotBlank() } ?: result.composition
        composingText.clear()
        composingText.append(displayComposition)
        if (displayComposition.isNotBlank() || result.showCandidates) {
            consumeClipboardSuggestion()
        }
        updateComposeView()

        if (result.showCandidates) {
            updateCandidateEntries(candidateEntriesForDisplay(result.candidateEntries))
        } else {
            if (result.composition.isBlank()) {
                t9PinyinDigits.clear()
                updateT9PinyinOptions()
            }
            updateCandidates(emptyList())
        }

        return result.handled
    }

    private fun candidateEntriesForDisplay(entries: List<CandidateEntry>): List<CandidateEntry> {
        if (!isT9Mode || currentMode != InputMode.PINYIN || t9PinyinDigits.isEmpty()) return entries
        return entries.map { it.copy(comment = "") }
    }

    private fun updateT9InferredPinyin(entries: List<CandidateEntry>) {
        if (!isT9Mode || currentMode != InputMode.PINYIN || t9PinyinDigits.isEmpty()) return
        if (t9PinyinDigits.contains('1')) return
        val segments = t9Segments()
        val inferred = entries.firstOrNull()
            ?.comment
            ?.trim()
            ?.split(Regex("\\s+"))
            .orEmpty()
        if (inferred.size < segments.size) return
        segments.indices.forEach { index ->
            if (!t9SelectedPinyinBySegment.containsKey(index)) {
                t9InferredPinyinBySegment[index] = inferred[index]
            }
        }
    }

    private fun t9Segments(): List<String> = T9Pinyin.segmentDigits(t9PinyinDigits.toString())

    private fun t9DisplayComposition(): String {
        if (!isT9Mode || currentMode != InputMode.PINYIN || t9PinyinDigits.isEmpty()) return ""
        return t9Segments().mapIndexed { index, segment ->
            t9SelectedPinyinBySegment[index]
                ?: t9InferredPinyinBySegment[index]
                ?: T9Pinyin.defaultPinyinFor(segment)
        }.joinToString("'")
    }

    private fun submitMoqiKey(
        keyCode: Int,
        charCode: Int = 0,
        fallbackOnSuccessOnly: Boolean = false,
        fallbackOnFailure: Boolean = false,
        retryFullPinyinOnUnhandled: Boolean = false,
        fallback: (() -> Unit)? = null
    ) {
        if (!::engineRunner.isInitialized) {
            showRimeInitializingIfNeeded()
            if (fallbackOnFailure) {
                fallback?.invoke()
            }
            return
        }
        engineRunner.keyDown(keyCode, charCode) { engineResult ->
            val result = engineResult.result
            Log.d(TAG, "engineResult seq=${engineResult.sequence} mode=$currentMode keyCode=$keyCode charCode=$charCode success=${result.success} handled=${result.handled} composition=${result.composition} commit=${result.commit} candidates=${result.candidates.size} error=${result.error}")
            val handled = applyMoqiResult(result)
            if (!handled && shouldRetryWithFullPinyin(result, charCode, retryFullPinyinOnUnhandled)) {
                retryKeyWithFullPinyin(keyCode, charCode)
                return@keyDown
            }
            val shouldFallback = fallback != null && !handled &&
                ((fallbackOnSuccessOnly && result.success) || fallbackOnFailure || (!fallbackOnSuccessOnly && result.success))
            if (shouldFallback) {
                fallback?.invoke()
            }
        }
    }

    private fun shouldRetryWithFullPinyin(
        result: MoqiImeResult,
        charCode: Int,
        retryFullPinyinOnUnhandled: Boolean
    ): Boolean {
        if (!retryFullPinyinOnUnhandled || !result.success || result.handled) return false
        if (currentMode != InputMode.PINYIN) return false
        if (result.composition.isNotBlank() || result.commit.isNotBlank()) return false
        if (charCode !in 'a'.code..'z'.code && charCode !in 'A'.code..'Z'.code) return false
        return currentSchemaId.contains("double_pinyin", ignoreCase = true)
    }

    private fun retryKeyWithFullPinyin(keyCode: Int, charCode: Int) {
        val targetSchema = "rime_frost"
        Log.d(TAG, "retry unhandled alphabet key with full pinyin schema current=$currentSchemaId target=$targetSchema charCode=$charCode")
        engineRunner.selectSchema(targetSchema) { schemaResult, schemaId ->
            if (!schemaResult.result.success) {
                applyMoqiResult(schemaResult.result)
                return@selectSchema
            }
            applySchemaLayout(schemaId.ifBlank { targetSchema })
            engineRunner.keyDown(keyCode, charCode) { retryResult ->
                applyMoqiResult(retryResult.result)
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
        updateCandidateEntries(candidates.map { CandidateEntry(it, "") })
    }

    private fun updateCandidateEntries(entries: List<CandidateEntry>) {
        candidateView?.setCandidateEntries(withClipboardCandidate(entries))
    }

    private fun withClipboardCandidate(entries: List<CandidateEntry>): List<CandidateEntry> {
        if (entries.isNotEmpty() || composingText.isNotBlank()) return entries
        val clipboardText = currentClipboardText() ?: return entries
        if (clipboardText == dismissedClipboardText) return entries
        val normalizedPreview = clipboardText
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(CLIPBOARD_CANDIDATE_PREVIEW_MAX)
        if (normalizedPreview.isBlank()) return entries
        val clipboardEntry = CandidateEntry(
            text = normalizedPreview,
            comment = "剪贴板",
            source = CandidateEntrySource.CLIPBOARD,
            commitText = clipboardText
        )
        if (entries.firstOrNull()?.source == CandidateEntrySource.CLIPBOARD) return entries
        return listOf(clipboardEntry) + entries
    }

    private fun currentClipboardText(): String? {
        val manager = clipboardManager ?: (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.also {
            clipboardManager = it
        } ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return null
        return text.takeIf { it.isNotBlank() }
    }

    private fun registerClipboardListener() {
        val manager = clipboardManager ?: (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.also {
            clipboardManager = it
        } ?: return
        manager.removePrimaryClipChangedListener(clipboardChangeListener)
        manager.addPrimaryClipChangedListener(clipboardChangeListener)
    }

    private fun consumeClipboardSuggestion() {
        val text = currentClipboardText() ?: return
        dismissedClipboardText = text
    }

    private fun commitClipboardCandidate(entry: CandidateEntry) {
        dismissedClipboardText = entry.commitText
        currentInputConnection.commitText(entry.commitText, 1)
        clearTextEngineState()
    }

    private fun updateComposeView() {
        val displayText = t9DisplayComposition().takeIf { it.isNotBlank() } ?: composingText.toString()
        composeView?.setComposingText(displayText)
    }

    private fun showRimeInitializingIfNeeded() {
        if (isEngineInitializing && guidForMode(currentMode) == Mobilebridge.GUIDRime && composingText.isEmpty()) {
            composeView?.setComposingText(RIME_INIT_MESSAGE)
        }
    }

    private fun clearRimeInitializingMessage() {
        if (composeView == null || composingText.isNotEmpty()) return
        composeView?.setComposingText("")
    }

    @SuppressLint("MissingPermission")
    private fun performKeyVibration() {
        if (powerManager.isPowerSaveMode) return
        val activeVibrator = vibrator ?: return
        if (!activeVibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeVibrator.vibrate(VibrationEffect.createOneShot(KEY_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            activeVibrator.vibrate(KEY_VIBRATION_MS)
        }
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
        updateShiftKeyState()
        refreshCandidateStatusBanner()
    }

    /**
     * 候选条空态「墨奇输入法」后的文案：与设置里「输入方案」summary 相同（当前方案 id 对应的 name，如「白霜小鹤双拼」）。
     * 仅用 [RimeSchemaEntry.id] 精确匹配，避免误用其它条目的 [RimeSchemaEntry.selected]。
     */
    private fun refreshCandidateStatusBanner() {
        val cv = candidateView ?: return
        if (!::engineRunner.isInitialized) {
            cv.setImeStatusDetail("")
            return
        }
        engineRunner.menuState { _, _, _, schemas, schemaId ->
            val view = candidateView ?: return@menuState
            val sid = currentSchemaId.ifBlank { schemaId }
            view.setImeStatusDetail(rimeSchemaDisplayNameLikeSettings(schemas, sid))
        }
    }

    /** 与 [com.moqi.im.settings.SettingsFragment.updateSchemaPreference] 中 summary 一致。 */
    private fun rimeSchemaDisplayNameLikeSettings(schemas: List<RimeSchemaEntry>, currentSchemaId: String): String {
        if (schemas.isEmpty()) return currentSchemaId
        val value = currentSchemaId.ifBlank {
            schemas.firstOrNull { it.selected }?.id ?: schemas.first().id
        }
        return schemas.firstOrNull { it.id == value }?.name?.takeIf { it.isNotBlank() } ?: value
    }

    private fun updateShiftKeyState() {
        val state = when {
            shiftLocked -> KeyboardView.ShiftState.LOCKED_UPPER
            shiftActive -> KeyboardView.ShiftState.TEMP_UPPER
            else -> KeyboardView.ShiftState.LOWER
        }
        keyboardView?.setShiftState(state)
    }

    private fun setCandidateExpanded(expanded: Boolean) {
        val view = candidateView ?: return
        if (expanded) {
            expandedCandidatePageIndex = 0
            isExpandedCandidateLoading = false
            expandedCandidateInitialPrefetchRemaining = INITIAL_EXPANDED_PREFETCH_PAGES
        } else {
            expandedCandidateInitialPrefetchRemaining = 0
        }
        if (keyboardMenuView?.visibility != View.VISIBLE) {
            keyboardView?.visibility = if (expanded) View.GONE else View.VISIBLE
        }
        val targetHeight = if (expanded) {
            inputPanelView?.height?.takeIf { it > 0 } ?: dp(260)
        } else {
            resources.getDimensionPixelSize(com.moqi.im.R.dimen.candidate_height)
        }
        val params = view.layoutParams
        if (params.height != targetHeight) {
            params.height = targetHeight
            view.layoutParams = params
        }
        if (keyboardMenuView?.visibility != View.VISIBLE) {
            if (!expanded) {
                updateKeyboard()
            }
        }
        if (expanded) {
            view.bringToFront()
        }
        inputPanelView?.requestLayout()
        view.post {
            view.requestLayout()
            view.invalidate()
        }
    }

    private fun loadNextExpandedCandidatePage() {
        if (!::engineRunner.isInitialized) {
            showRimeInitializingIfNeeded()
            return
        }
        if (isExpandedCandidateLoading) return
        isExpandedCandidateLoading = true
        val isInitialPrefetch = expandedCandidateInitialPrefetchRemaining > 0
        engineRunner.changeCandidatePage(backward = false) { engineResult ->
            val result = engineResult.result
            if (!result.success) {
                isExpandedCandidateLoading = false
                expandedCandidateInitialPrefetchRemaining = 0
                applyMoqiResult(result)
                return@changeCandidatePage
            }
            isExpandedCandidateLoading = false
            if (result.handled && result.showCandidates && result.candidateEntries.isNotEmpty()) {
                expandedCandidatePageIndex += 1
                if (isInitialPrefetch) {
                    expandedCandidateInitialPrefetchRemaining =
                        (expandedCandidateInitialPrefetchRemaining - 1).coerceAtLeast(0)
                }
                candidateView?.appendExpandedCandidateEntries(
                    pageIndex = expandedCandidatePageIndex,
                    entries = result.candidateEntries
                )
                if (expandedCandidateInitialPrefetchRemaining > 0) {
                    handler.post { loadNextExpandedCandidatePage() }
                }
            } else {
                expandedCandidateInitialPrefetchRemaining = 0
                candidateView?.appendExpandedCandidateEntries(
                    pageIndex = expandedCandidatePageIndex,
                    entries = emptyList()
                )
            }
        }
    }

    private fun selectExpandedCandidate(pageIndex: Int, pageLocalIndex: Int) {
        if (pageIndex == 0) {
            val view = candidateView
            val entry = view?.getCurrentPageCandidateEntry(pageLocalIndex)
            if (entry?.source == CandidateEntrySource.CLIPBOARD) {
                commitClipboardCandidate(entry)
                return
            }
        }
        if (!::engineRunner.isInitialized) {
            showRimeInitializingIfNeeded()
            return
        }
        val targetLocalIndex = if (pageIndex == 0) {
            candidateView?.rimeCurrentPageCandidateIndexFor(pageLocalIndex) ?: -1
        } else {
            pageLocalIndex
        }
        if (targetLocalIndex < 0) return
        moveToExpandedCandidatePage(pageIndex) {
            engineRunner.selectCandidate(targetLocalIndex) { engineResult ->
                applyMoqiResult(engineResult.result)
            }
        }
    }

    private fun moveToExpandedCandidatePage(targetPageIndex: Int, onReady: () -> Unit) {
        val delta = targetPageIndex - expandedCandidatePageIndex
        if (delta == 0) {
            onReady()
            return
        }
        val backward = delta < 0
        val steps = kotlin.math.abs(delta)
        moveExpandedCandidatePageStep(backward, steps, onReady)
    }

    private fun moveExpandedCandidatePageStep(backward: Boolean, remainingSteps: Int, onReady: () -> Unit) {
        if (remainingSteps <= 0) {
            onReady()
            return
        }
        engineRunner.changeCandidatePage(backward = backward) { engineResult ->
            val result = engineResult.result
            if (!result.success || !result.handled) {
                applyMoqiResult(result)
                return@changeCandidatePage
            }
            expandedCandidatePageIndex += if (backward) -1 else 1
            moveExpandedCandidatePageStep(backward, remainingSteps - 1, onReady)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showMenuPanel() {
        setCandidateExpanded(false)
        keyboardView?.visibility = View.GONE
        keyboardMenuView?.visibility = View.VISIBLE
        keyboardMenuView?.bringToFront()
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

    private fun downloadSchemeSet(rawUrl: String) {
        val url = rawUrl.trim()
        val name = schemeSetNameFromUrl(url)
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            showMessage("只支持 http/https URL")
            return
        }
        if (name.isBlank()) {
            showMessage("无法从 URL 提取方案集名称")
            return
        }
        composeView?.setComposingText("正在下载方案集...")
        downloadExecutor.execute {
            val result = runCatching {
                val root = File(applicationContext.filesDir, "Moqi").apply { mkdirs() }
                val target = File(root, name).canonicalFile
                val temp = File(root, ".$name-download").canonicalFile
                if (!target.path.startsWith(root.canonicalPath + File.separator)) {
                    error("方案集名称不合法")
                }
                if (!temp.path.startsWith(root.canonicalPath + File.separator)) {
                    error("临时目录不合法")
                }
                if (target.exists()) {
                    target.deleteRecursively()
                }
                if (temp.exists()) {
                    temp.deleteRecursively()
                }
                temp.mkdirs()
                target.mkdirs()
                downloadAndUnzip(url, temp)
                installDownloadedSchemeSet(temp, target)
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

    private fun installDownloadedSchemeSet(temp: File, target: File) {
        val source = normalizedSchemeSetRoot(temp)
        target.mkdirs()
        source.copyRecursively(target, overwrite = true)
        temp.deleteRecursively()
        if (!target.walkTopDown().any { it.isFile && it.extension.equals("yaml", ignoreCase = true) }) {
            error("ZIP 中没有发现 Rime YAML 配置")
        }
    }

    private fun normalizedSchemeSetRoot(temp: File): File {
        val entries = temp.listFiles().orEmpty().filterNot { it.name.startsWith(".") }
        val hasRootYaml = entries.any { it.isFile && it.extension.equals("yaml", ignoreCase = true) }
        if (hasRootYaml) {
            return temp
        }
        val singleDir = entries.singleOrNull { it.isDirectory }
        return singleDir ?: temp
    }

    private fun sanitizeSchemeSetName(name: String): String {
        return name.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_").trim('_', '.', '-')
    }

    private fun schemeSetNameFromUrl(rawUrl: String): String {
        val parsed = runCatching { Uri.parse(rawUrl) }.getOrNull()
        val fileName = parsed?.lastPathSegment
            ?.substringBefore('?')
            ?.substringBefore('#')
            .orEmpty()
            .ifBlank {
                rawUrl.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
            }
        return sanitizeSchemeSetName(
            fileName
                .removeSuffix(".zip")
                .removeSuffix(".ZIP")
                .ifBlank { "downloaded_scheme" }
        )
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
        if (currentMode == InputMode.PINYIN || currentMode == InputMode.WUBI) {
            lastChineseMode = currentMode
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
        clipboardManager?.removePrimaryClipChangedListener(clipboardChangeListener)
        downloadExecutor.shutdown()
        keyboardView = null
        keyboardMenuView = null
        candidateView = null
        composeView = null
        inputPanelView = null
        imeView = null
    }
}
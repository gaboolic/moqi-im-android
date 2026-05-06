package com.moqi.im.core

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.moqi.im.engine.EngineFactory
import com.moqi.im.engine.InputEngine
import com.moqi.im.engine.InputMode
import com.moqi.im.keyboard.CandidateView
import com.moqi.im.keyboard.ComposeView
import com.moqi.im.keyboard.KeyCode
import com.moqi.im.keyboard.KeyboardView

class MoqiInputMethodService : InputMethodService() {

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
    private var engine: InputEngine = EngineFactory.create(InputMode.PINYIN)
    private var composingText: StringBuilder = StringBuilder()

    private var keyboardView: KeyboardView? = null
    private var candidateView: CandidateView? = null
    private var composeView: ComposeView? = null
    private var imeView: View? = null

    private var shiftActive: Boolean = false
    private var shiftLocked: Boolean = false
    private var isT9Mode: Boolean = false
    private var modeBeforeVoice: InputMode = InputMode.PINYIN

    private val handler = Handler(Looper.getMainLooper())
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
    }

    override fun onCreateInputView(): View {
        imeView = layoutInflater.inflate(com.moqi.im.R.layout.ime_view, null)
        keyboardView = imeView?.findViewById(com.moqi.im.R.id.keyboard_view)
        candidateView = imeView?.findViewById(com.moqi.im.R.id.candidate_view)
        composeView = imeView?.findViewById(com.moqi.im.R.id.compose_view)

        keyboardView?.setOnKeyListener { keyCode, isShifted ->
            handleKey(keyCode, isShifted)
        }

        candidateView?.setOnCandidateSelectedListener { candidate ->
            commitCandidate(candidate)
        }

        updateKeyboard()
        return imeView!!
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        composingText.clear()
        engine.reset()
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
            KeyCode.VOICE -> enterVoiceMode()
            KeyCode.EXIT_VOICE -> exitVoiceMode()
            KeyCode.COMMA -> commitText("，")
            KeyCode.PERIOD -> commitText("。")
            KeyCode.SWITCH_TO_QWERTY -> {
                isT9Mode = false
                if (currentMode == InputMode.VOICE) {
                    currentMode = modeBeforeVoice
                    engine = EngineFactory.create(currentMode)
                }
                updateKeyboard()
                resetT9State()
            }
            KeyCode.SWITCH_TO_T9 -> {
                isT9Mode = true
                if (currentMode == InputMode.VOICE) {
                    currentMode = modeBeforeVoice
                    engine = EngineFactory.create(currentMode)
                }
                updateKeyboard()
                resetT9State()
            }
            in KeyCode.T9_1..KeyCode.T9_POUND -> handleT9Key(keyCode)
            else -> {
                val ch = keyCodeToChar(keyCode, isShifted || shiftActive)
                if (ch != null) {
                    handleCharacter(ch)
                }
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
        composingText.append(digit)
        val candidates = engine.processInput(composingText.toString())
        updateCandidates(candidates)
        updateComposeView()
    }

    private fun resetT9State() {
        t9TapCount = 0
        t9CurrentKey = 0
        t9Runnable = null
    }

    private fun commitText(text: String) {
        currentInputConnection.commitText(text, 1)
    }

    private fun handleCharacter(ch: Char) {
        composingText.append(ch)
        val candidates = engine.processInput(composingText.toString())
        updateCandidates(candidates)
        updateComposeView()
        if (shiftActive && !shiftLocked) {
            shiftActive = false
            keyboardView?.setShifted(false)
        }
    }

    private fun handleBackspace() {
        if (composingText.isNotEmpty()) {
            composingText.deleteCharAt(composingText.length - 1)
            if (composingText.isEmpty()) {
                engine.reset()
                candidateView?.setCandidates(emptyList())
            } else {
                val candidates = engine.processInput(composingText.toString())
                candidateView?.setCandidates(candidates)
            }
            updateComposeView()
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    private fun handleEnter() {
        if (composingText.isNotEmpty()) {
            currentInputConnection.commitText(composingText.toString(), 1)
            composingText.clear()
            engine.reset()
            candidateView?.setCandidates(emptyList())
            updateComposeView()
        } else {
            sendKeyChar('\n')
        }
    }

    private fun handleSpace() {
        if (composingText.isNotEmpty()) {
            val first = candidateView?.getFirstCandidate()
            if (first != null) {
                commitCandidate(first)
            } else {
                currentInputConnection.commitText(composingText.toString(), 1)
                composingText.clear()
                engine.reset()
                candidateView?.setCandidates(emptyList())
                updateComposeView()
            }
        } else {
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
    }

    private fun exitVoiceMode() {
        currentMode = modeBeforeVoice
        engine = EngineFactory.create(currentMode)
        composingText.clear()
        engine.reset()
        candidateView?.setCandidates(emptyList())
        updateComposeView()
        updateKeyboard()
    }

    private fun switchMode(mode: InputMode) {
        currentMode = mode
        engine = EngineFactory.create(mode)
        composingText.clear()
        engine.reset()
        candidateView?.setCandidates(emptyList())
        updateComposeView()
        updateKeyboard()
    }

    private fun commitCandidate(candidate: String) {
        currentInputConnection.commitText(candidate, 1)
        composingText.clear()
        engine.reset()
        candidateView?.setCandidates(emptyList())
        updateComposeView()
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

    private fun keyCodeToChar(keyCode: Int, shifted: Boolean): Char? {
        return when (keyCode) {
            KeyEvent.KEYCODE_A -> if (shifted) 'A' else 'a'
            KeyEvent.KEYCODE_B -> if (shifted) 'B' else 'b'
            KeyEvent.KEYCODE_C -> if (shifted) 'C' else 'c'
            KeyEvent.KEYCODE_D -> if (shifted) 'D' else 'd'
            KeyEvent.KEYCODE_E -> if (shifted) 'E' else 'e'
            KeyEvent.KEYCODE_F -> if (shifted) 'F' else 'f'
            KeyEvent.KEYCODE_G -> if (shifted) 'G' else 'g'
            KeyEvent.KEYCODE_H -> if (shifted) 'H' else 'h'
            KeyEvent.KEYCODE_I -> if (shifted) 'I' else 'i'
            KeyEvent.KEYCODE_J -> if (shifted) 'J' else 'j'
            KeyEvent.KEYCODE_K -> if (shifted) 'K' else 'k'
            KeyEvent.KEYCODE_L -> if (shifted) 'L' else 'l'
            KeyEvent.KEYCODE_M -> if (shifted) 'M' else 'm'
            KeyEvent.KEYCODE_N -> if (shifted) 'N' else 'n'
            KeyEvent.KEYCODE_O -> if (shifted) 'O' else 'o'
            KeyEvent.KEYCODE_P -> if (shifted) 'P' else 'p'
            KeyEvent.KEYCODE_Q -> if (shifted) 'Q' else 'q'
            KeyEvent.KEYCODE_R -> if (shifted) 'R' else 'r'
            KeyEvent.KEYCODE_S -> if (shifted) 'S' else 's'
            KeyEvent.KEYCODE_T -> if (shifted) 'T' else 't'
            KeyEvent.KEYCODE_U -> if (shifted) 'U' else 'u'
            KeyEvent.KEYCODE_V -> if (shifted) 'V' else 'v'
            KeyEvent.KEYCODE_W -> if (shifted) 'W' else 'w'
            KeyEvent.KEYCODE_X -> if (shifted) 'X' else 'x'
            KeyEvent.KEYCODE_Y -> if (shifted) 'Y' else 'y'
            KeyEvent.KEYCODE_Z -> if (shifted) 'Z' else 'z'
            KeyEvent.KEYCODE_1 -> '1'
            KeyEvent.KEYCODE_2 -> '2'
            KeyEvent.KEYCODE_3 -> '3'
            KeyEvent.KEYCODE_4 -> '4'
            KeyEvent.KEYCODE_5 -> '5'
            KeyEvent.KEYCODE_6 -> '6'
            KeyEvent.KEYCODE_7 -> '7'
            KeyEvent.KEYCODE_8 -> '8'
            KeyEvent.KEYCODE_9 -> '9'
            KeyEvent.KEYCODE_0 -> '0'
            else -> null
        }
    }

    private fun loadInputModePreference() {
        val prefs = getSharedPreferences("moqi_im_prefs", MODE_PRIVATE)
        val modeStr = prefs.getString("input_mode", "pinyin") ?: "pinyin"
        currentMode = when (modeStr) {
            "wubi" -> InputMode.WUBI
            "english" -> InputMode.ENGLISH
            else -> InputMode.PINYIN
        }
        engine = EngineFactory.create(currentMode)
    }

    private fun launchSettings() {
        val intent = android.content.Intent(this, com.moqi.im.settings.SettingsActivity::class.java)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        keyboardView = null
        candidateView = null
        composeView = null
        imeView = null
    }
}
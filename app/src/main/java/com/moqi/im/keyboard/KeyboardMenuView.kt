package com.moqi.im.keyboard

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.moqi.im.engine.RimeMenuEntry
import com.moqi.im.engine.RimeSchemaEntry

class KeyboardMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    data class MenuState(
        val menuEntries: List<RimeMenuEntry> = emptyList(),
        val schemeSets: List<String> = emptyList(),
        val currentSchemeSet: String = "",
        val schemas: List<RimeSchemaEntry> = emptyList(),
        val currentSchemaId: String = ""
    )

    interface Callback {
        fun onBack()
        fun onCommand(commandId: Int)
        fun onSchemeSet(name: String)
        fun onSchema(schemaId: String)
        fun onInputMethodPicker()
        fun onVoiceInput()
        fun onOpenSettings()
        fun onDownloadScheme(url: String)
    }

    var callback: Callback? = null

    private val density = resources.displayMetrics.density
    private val mainLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
    }
    private var expandedSelectionList: View? = null
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(10))
    }
    private val downloadUrl = EditText(context).apply {
        hint = "方案集 ZIP URL"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true)
        isFocusable = true
        isFocusableInTouchMode = true
        setSelectAllOnFocus(false)
        setOnClickListener { showKeyboardForUrlInput() }
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showKeyboardForUrlInput()
            }
        }
    }

    init {
        setBackgroundColor(Color.rgb(240, 240, 245))
        mainLayout.addView(createHeader(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        scrollView.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        mainLayout.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(mainLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        render(MenuState())
    }

    fun render(state: MenuState) {
        dismissSelectionList()
        content.removeAllViews()

        val commandItems = state.menuEntries.filter { it.commandId != 0 && it.text.isNotBlank() }
        val statusItems = commandItems.filter { it.group.isBlank() }.take(12)
        if (statusItems.isNotEmpty()) {
            addSection("输入状态")
            addGrid(statusItems.map { entry ->
                Action(entry.displayText()) { callback?.onCommand(entry.commandId) }
            })
        }

        if (state.schemeSets.isNotEmpty()) {
            addSection("方案集")
            addSelector(
                label = state.currentSchemeSet.ifBlank { state.schemeSets.first() },
                items = state.schemeSets.map { name ->
                    SelectionItem(
                        label = name,
                        selected = name == state.currentSchemeSet
                    ) {
                        callback?.onSchemeSet(name)
                    }
                }
            )
        }

        if (state.schemas.isNotEmpty()) {
            addSection("输入方案")
            val currentSchema = state.schemas.firstOrNull {
                it.id == state.currentSchemaId || it.selected
            } ?: state.schemas.first()
            addSelector(
                label = currentSchema.name.ifBlank { currentSchema.id },
                items = state.schemas.map { schema ->
                    val selected = schema.id == state.currentSchemaId || schema.selected
                    SelectionItem(
                        label = schema.name.ifBlank { schema.id },
                        selected = selected
                    ) {
                        callback?.onSchema(schema.id)
                    }
                }
            )
        }

        val configItems = commandItems.filter {
            it.text.contains("刷新") || it.text.contains("更新") || it.text.contains("配置")
        }.distinctBy { it.commandId }
        if (configItems.isNotEmpty()) {
            addSection("配置")
            addGrid(configItems.map { entry ->
                Action(entry.displayText()) { callback?.onCommand(entry.commandId) }
            })
        }

        addDownloadSection()
    }

    private fun createHeader(): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(2))
            addView(menuButton("←") { callback?.onBack() }, LinearLayout.LayoutParams(dp(50), dp(40)))
            addView(TextView(context).apply {
                text = "墨奇菜单"
                textSize = 17f
                setTextColor(Color.rgb(26, 26, 46))
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(menuButton("设置") { callback?.onOpenSettings() }, LinearLayout.LayoutParams(dp(68), dp(40)))
        }
    }

    private fun addSection(title: String) {
        content.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(Color.rgb(96, 96, 128))
            setPadding(0, dp(8), 0, dp(3))
        })
    }

    private fun addGrid(actions: List<Action>, columns: Int = 4, itemHeight: Int = 56) {
        actions.chunked(columns).forEach { rowActions ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowActions.forEach { action ->
                val lp = LinearLayout.LayoutParams(0, dp(itemHeight), 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                row.addView(menuButton(action.label, action.onClick), lp)
            }
            repeat(columns - rowActions.size) {
                row.addView(View(context), LinearLayout.LayoutParams(0, dp(itemHeight), 1f))
            }
            content.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun addSelector(label: String, items: List<SelectionItem>) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val button = menuButton("$label  ▾") {}
        val list = createSelectionList(items).apply {
            visibility = GONE
        }
        button.setOnClickListener {
            if (list.visibility == VISIBLE) {
                dismissSelectionList()
            } else {
                dismissSelectionList()
                list.visibility = VISIBLE
                expandedSelectionList = list
            }
        }
        container.addView(button, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(dp(3), dp(3), dp(3), dp(6))
        })
        container.addView(list, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(3), 0, dp(3), dp(6))
        })
        content.addView(container, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun createSelectionList(items: List<SelectionItem>): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 246, 250))
            items.forEach { item ->
                addView(selectionRow(item), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
            }
        }
    }

    private fun dismissSelectionList() {
        expandedSelectionList?.visibility = GONE
        expandedSelectionList = null
    }

    override fun onDetachedFromWindow() {
        dismissSelectionList()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            dismissSelectionList()
        }
    }

    private fun selectionRow(item: SelectionItem): TextView {
        return TextView(context).apply {
            text = if (item.selected) "✓ ${item.label}" else item.label
            textSize = 16f
            setTextColor(Color.rgb(26, 26, 46))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener {
                dismissSelectionList()
                item.onClick.onClick(it)
            }
        }
    }

    private fun addDownloadSection() {
        addSection("下载新方案集")
        content.addView(downloadUrl, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        content.addView(TextView(context).apply {
            text = "方案集名称会自动从 URL 文件名提取"
            textSize = 13f
            setTextColor(Color.rgb(96, 96, 128))
            setPadding(0, dp(4), 0, dp(6))
        })
        content.addView(menuButton("下载并切换") {
            callback?.onDownloadScheme(downloadUrl.text.toString())
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
    }

    private fun showKeyboardForUrlInput() {
        downloadUrl.requestFocus()
        downloadUrl.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(downloadUrl, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun menuButton(textValue: String, onClick: View.OnClickListener): Button {
        return Button(context).apply {
            text = textValue
            textSize = 14f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener(onClick)
        }
    }

    private fun RimeMenuEntry.displayText(): String {
        val prefix = if (checked) "✓ " else ""
        return prefix + text.replace("(&I)", "").replace("(&P)", "").replace("(&R)", "")
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private data class Action(
        val label: String,
        val onClick: View.OnClickListener
    )

    private data class SelectionItem(
        val label: String,
        val selected: Boolean,
        val onClick: View.OnClickListener
    )
}

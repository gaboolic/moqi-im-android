package com.moqi.im.settings

import android.app.ProgressDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.DragEvent
import android.widget.EditText
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.EditTextPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.moqi.im.theme.ThemeSurfaceColors
import com.moqi.im.R
import com.moqi.im.cloudclipboard.CloudClipboardPrefs
import com.moqi.im.cloudclipboard.WebDavUrlPolicy
import com.moqi.im.cloudclipboard.CloudClipboardSync
import com.moqi.im.cloudclipboard.UserDictWebDavSync
import kotlinx.coroutines.runBlocking
import com.moqi.im.data.RimeSafSync
import com.moqi.im.engine.MoqiImeSession
import com.moqi.im.engine.RimeSchemaEntry
import com.moqi.im.keyboard.KeyboardBottomRowLayout
import com.moqi.im.moqiAndroidDataDir
import com.moqi.im.theme.ThemePalette
import com.moqi.im.util.ImeDebugLog
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import mobilebridge.Mobilebridge

class SettingsFragment : PreferenceFragmentCompat() {
    companion object {
        private const val PREFS_NAME = ThemePalette.PREFS_NAME
        private const val PREF_RIME_SHARED_DIR_URI = "rime_shared_dir_uri"
        private const val DEFAULT_KEYBOARD_HEIGHT_PERCENT = 100
        private const val RIME_DEPLOY_COMMAND_ID = 10
        private const val RIME_READY_ATTEMPTS = 120
        private const val RIME_READY_DELAY_MS = 150L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private var progressDialog: ProgressDialog? = null
    private val rimeSharedDirLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        val context = context ?: return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
        context.getSharedPreferences(PREFS_NAME, 0)
            .edit()
            .putString(PREF_RIME_SHARED_DIR_URI, uri.toString())
            .apply()
        updateRimeSharedDirSummary()
        Toast.makeText(context, R.string.pref_rime_shared_dir_saved, Toast.LENGTH_SHORT).show()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = PREFS_NAME
        setPreferencesFromResource(R.xml.preferences, rootKey)
        executor = Executors.newSingleThreadExecutor()

        val modePref = findPreference<ListPreference>("input_mode")
        modePref?.setOnPreferenceChangeListener { _, newValue ->
            val prefs = requireActivity().getSharedPreferences(PREFS_NAME, 0)
            prefs.edit().putString("input_mode", newValue as String).apply()
            true
        }

        findPreference<ListPreference>("rime_scheme_set")?.setOnPreferenceChangeListener { _, newValue ->
            selectRimeSchemeSet(newValue as String)
            true
        }

        findPreference<ListPreference>("rime_schema")?.setOnPreferenceChangeListener { _, newValue ->
            selectRimeSchema(newValue as String)
            true
        }
        setupRimeSharedDirectoryPreferences()

        findPreference<ListPreference>(ThemePalette.PREF_KEY)?.setOnPreferenceChangeListener { _, _ ->
            mainHandler.post { applyThemeBackground() }
            true
        }

        val keyboardHeightPref = findPreference<SeekBarPreference>("keyboard_height")
        keyboardHeightPref?.let { pref ->
            updateKeyboardHeightSummary(pref, pref.value)
            pref.setOnPreferenceChangeListener { preference, newValue ->
                updateKeyboardHeightSummary(preference, newValue as Int)
                true
            }
        }

        findPreference<Preference>("keyboard_height_reset")?.setOnPreferenceClickListener {
            keyboardHeightPref?.value = DEFAULT_KEYBOARD_HEIGHT_PERCENT
            keyboardHeightPref?.let { pref ->
                updateKeyboardHeightSummary(pref, DEFAULT_KEYBOARD_HEIGHT_PERCENT)
            }
            Toast.makeText(requireContext(), R.string.pref_keyboard_height_reset_done, Toast.LENGTH_SHORT).show()
            true
        }

        setupBottomRowLayoutPreference()
        setupCloudClipboardPreferences()

        val soundPref = findPreference<SwitchPreferenceCompat>("key_sound")
        soundPref?.setOnPreferenceChangeListener { _, newValue ->
            val prefs = requireActivity().getSharedPreferences(PREFS_NAME, 0)
            prefs.edit().putBoolean("key_sound", newValue as Boolean).apply()
            true
        }

        val vibratePref = findPreference<SwitchPreferenceCompat>("key_vibrate")
        vibratePref?.setOnPreferenceChangeListener { _, newValue ->
            val prefs = requireActivity().getSharedPreferences(PREFS_NAME, 0)
            prefs.edit().putBoolean("key_vibrate", newValue as Boolean).apply()
            true
        }

        val debugPref = findPreference<SwitchPreferenceCompat>(ImeDebugLog.PREF_KEY)
        debugPref?.setOnPreferenceChangeListener { _, newValue ->
            ImeDebugLog.setEnabled(newValue as Boolean)
            true
        }

        val privacyPref = findPreference<androidx.preference.Preference>("privacy")
        privacyPref?.setOnPreferenceClickListener {
            Toast.makeText(requireContext(), R.string.pref_privacy_summary, Toast.LENGTH_LONG).show()
            true
        }

        loadRimePreferences(showProgress = true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyThemeBackground()
    }

    override fun onResume() {
        super.onResume()
        applyThemeBackground()
    }

    override fun onCreateAdapter(preferenceScreen: androidx.preference.PreferenceScreen): RecyclerView.Adapter<*> =
        object : PreferenceGroupAdapter(preferenceScreen) {
            override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                // Bind recycled and newly inserted rows too, not only the currently visible children.
                val colors = ThemeSurfaceColors.from(ThemePalette.current(requireContext()))
                fun applyText(view: View) {
                    if (view is TextView) view.setTextColor(colors.onSurface)
                    if (view is ViewGroup) {
                        for (index in 0 until view.childCount) applyText(view.getChildAt(index))
                    }
                }
                applyText(holder.itemView)
            }
        }

    private fun applyThemeBackground() {
        val colors = ThemeSurfaceColors.from(ThemePalette.current(requireContext()))
        view?.setBackgroundColor(colors.surface)
        listView.setBackgroundColor(colors.surface)
        (activity as? SettingsActivity)?.applyThemeColors()
        listView.adapter?.notifyDataSetChanged()
    }

    private data class RowEditorState(
        val row: KeyboardBottomRowLayout.Row,
        val order: MutableList<String>
    )

    private fun setupBottomRowLayoutPreference() {
        val pref = findPreference<Preference>("keyboard_bottom_row_layout") ?: return
        pref.setOnPreferenceClickListener {
            showBottomRowLayoutDialog()
            true
        }
    }

    private fun showBottomRowLayoutDialog() {
        val context = requireContext()
        val states = KeyboardBottomRowLayout.Row.values().map { row ->
            RowEditorState(row, KeyboardBottomRowLayout.order(context, row).toMutableList())
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        content.addView(TextView(context).apply {
            text = getString(R.string.pref_keyboard_bottom_row_layout_hint)
            setTextColor(Color.DKGRAY)
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        })

        lateinit var renderAll: () -> Unit
        val rowViews = states.associateWith { state ->
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                renderBottomRowEditor(this, state) { renderAll() }
            }
        }
        renderAll = {
            rowViews.forEach { (state, rowView) ->
                renderBottomRowEditor(rowView, state) { renderAll() }
            }
        }

        states.forEach { state ->
            content.addView(TextView(context).apply {
                text = state.row.title
                textSize = 15f
                setTextColor(Color.BLACK)
                setPadding(0, dp(10), 0, dp(6))
            })
            content.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(rowViews.getValue(state))
            })
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.pref_keyboard_bottom_row_layout)
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("重置", null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                states.forEach { state ->
                    KeyboardBottomRowLayout.save(context, state.row, state.order)
                }
                Toast.makeText(context, R.string.pref_keyboard_bottom_row_layout_saved, Toast.LENGTH_SHORT).show()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                KeyboardBottomRowLayout.resetAll(context)
                states.forEach { state ->
                    state.order.clear()
                    state.order.addAll(state.row.defaultOrder)
                }
                renderAll()
                Toast.makeText(context, R.string.pref_keyboard_bottom_row_layout_reset, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun renderBottomRowEditor(
        rowView: LinearLayout,
        state: RowEditorState,
        onOrderChanged: () -> Unit
    ) {
        rowView.removeAllViews()
        state.order.forEach { keyId ->
            rowView.addView(createDraggableKeyView(keyId, state, onOrderChanged))
        }
    }

    private fun createDraggableKeyView(
        keyId: String,
        state: RowEditorState,
        onOrderChanged: () -> Unit
    ): TextView {
        val context = requireContext()
        return TextView(context).apply {
            text = KeyboardBottomRowLayout.displayLabel(keyId)
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(48)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFFECEFF7.toInt())
                setStroke(dp(1), 0xFFC4CAD8.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, dp(8), dp(8))
            }
            setOnLongClickListener {
                startDragAndDrop(
                    ClipData.newPlainText("keyboard_bottom_row_key", keyId),
                    View.DragShadowBuilder(this),
                    keyId,
                    View.DRAG_FLAG_OPAQUE
                )
                true
            }
            setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> event.localState is String
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        alpha = 0.65f
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        alpha = 1f
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        alpha = 1f
                        val draggedKeyId = event.localState as? String ?: return@setOnDragListener false
                        moveKeyBefore(state.order, draggedKeyId, keyId)
                        onOrderChanged()
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        alpha = 1f
                        true
                    }
                    else -> true
                }
            }
        }
    }

    private fun moveKeyBefore(order: MutableList<String>, draggedKeyId: String, targetKeyId: String) {
        if (draggedKeyId == targetKeyId) return
        val fromIndex = order.indexOf(draggedKeyId)
        val targetIndex = order.indexOf(targetKeyId)
        if (fromIndex == -1 || targetIndex == -1) return
        order.removeAt(fromIndex)
        val insertIndex = order.indexOf(targetKeyId).takeIf { it >= 0 } ?: targetIndex
        order.add(insertIndex, draggedKeyId)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun setupRimeSharedDirectoryPreferences() {
        findPreference<Preference>("rime_shared_dir")?.setOnPreferenceClickListener {
            rimeSharedDirLauncher.launch(null)
            true
        }
        findPreference<Preference>("rime_import_shared_dir")?.setOnPreferenceClickListener {
            syncRimeSharedDirectory(importFromShared = true)
            true
        }
        findPreference<Preference>("rime_export_shared_dir")?.setOnPreferenceClickListener {
            syncRimeSharedDirectory(importFromShared = false)
            true
        }
        updateRimeSharedDirSummary()
    }

    private fun updateRimeSharedDirSummary() {
        val uri = selectedRimeSharedDirUri()
        findPreference<Preference>("rime_shared_dir")?.summary = if (uri == null) {
            getString(R.string.pref_rime_shared_dir_summary)
        } else {
            getString(R.string.pref_rime_shared_dir_selected, Uri.decode(uri.lastPathSegment ?: uri.toString()))
        }
    }

    private fun selectedRimeSharedDirUri(): Uri? {
        val raw = context
            ?.getSharedPreferences(PREFS_NAME, 0)
            ?.getString(PREF_RIME_SHARED_DIR_URI, null)
            ?: return null
        return Uri.parse(raw)
    }

    private fun syncRimeSharedDirectory(importFromShared: Boolean) {
        val appContext = context?.applicationContext ?: return
        val treeUri = selectedRimeSharedDirUri()
        if (treeUri == null) {
            Toast.makeText(requireContext(), R.string.pref_rime_shared_dir_missing, Toast.LENGTH_SHORT).show()
            return
        }
        showRimeProgress(if (importFromShared) R.string.pref_rime_importing else R.string.pref_rime_exporting)
        executor?.execute {
            val result = runCatching {
                val runtimeRoot = File(appContext.moqiAndroidDataDir(), "Moqi")
                val sync = RimeSafSync(appContext)
                if (importFromShared) {
                    sync.importFrom(treeUri, runtimeRoot)
                    MoqiImeSession(
                        guid = Mobilebridge.GUIDRime,
                        androidDataDir = appContext.moqiAndroidDataDir().absolutePath
                    ).also { session ->
                        session.command(RIME_DEPLOY_COMMAND_ID)
                        waitForRimeSchemas(session)
                        session.close()
                    }
                } else {
                    sync.exportTo(runtimeRoot, treeUri)
                }
            }
            mainHandler.post {
                hideRimeProgress()
                if (!isAdded) return@post
                result.onSuccess {
                    Toast.makeText(
                        requireContext(),
                        if (importFromShared) R.string.pref_rime_import_done else R.string.pref_rime_export_done,
                        Toast.LENGTH_LONG
                    ).show()
                    if (importFromShared) {
                        loadRimePreferences()
                    }
                }.onFailure { error ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.pref_rime_sync_failed, error.message.orEmpty().ifBlank { error::class.java.simpleName }),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showRimeProgress(messageResId: Int) {
        val context = context ?: return
        progressDialog?.dismiss()
        progressDialog = ProgressDialog(context).apply {
            setTitle(R.string.pref_rime_operation_title)
            setMessage(getString(messageResId))
            setIndeterminate(true)
            setCancelable(false)
            show()
        }
        Toast.makeText(context, messageResId, Toast.LENGTH_LONG).show()
    }

    private fun hideRimeProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun updateKeyboardHeightSummary(pref: Preference, percent: Int) {
        pref.summary = getString(R.string.pref_keyboard_height_summary, percent)
    }

    private fun setupCloudClipboardPreferences() {
        updateCloudClipboardPasswordSummary()
        findPreference<EditTextPreference>("cloud_clipboard_url")?.setOnPreferenceChangeListener { _, newValue ->
            val raw = newValue as String
            val normalized = WebDavUrlPolicy.normalizeBaseUrl(raw)
            if (normalized != raw) {
                findPreference<EditTextPreference>("cloud_clipboard_url")?.text = normalized
            }
            true
        }
        findPreference<EditTextPreference>("cloud_clipboard_remote_path")?.setOnPreferenceChangeListener { _, newValue ->
            val raw = newValue as String
            val normalized = CloudClipboardPrefs.normalizeSettingsRoot(raw)
            if (normalized != raw) {
                findPreference<EditTextPreference>("cloud_clipboard_remote_path")?.text = normalized
            }
            true
        }
        findPreference<Preference>("cloud_clipboard_password")?.setOnPreferenceClickListener {
            showCloudClipboardPasswordDialog()
            true
        }
        findPreference<Preference>("cloud_clipboard_test")?.setOnPreferenceClickListener {
            testCloudClipboardConnection()
            true
        }
        findPreference<Preference>("user_dict_webdav_sync")?.setOnPreferenceClickListener {
            syncUserDictWithWebDav()
            true
        }
    }

    private fun updateCloudClipboardPasswordSummary() {
        val pref = findPreference<Preference>("cloud_clipboard_password") ?: return
        pref.summary = if (CloudClipboardPrefs.hasPassword(requireContext())) {
            getString(R.string.pref_cloud_clipboard_password_set)
        } else {
            getString(R.string.pref_cloud_clipboard_password_summary)
        }
    }

    private fun showCloudClipboardPasswordDialog() {
        val context = requireContext()
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.pref_cloud_clipboard_password_dialog_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = input.text?.toString().orEmpty()
                if (password.isNotEmpty()) {
                    CloudClipboardPrefs.savePassword(context, password)
                } else {
                    CloudClipboardPrefs.clearPassword(context)
                }
                updateCloudClipboardPasswordSummary()
                Toast.makeText(context, R.string.pref_cloud_clipboard_password_saved, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun testCloudClipboardConnection() {
        val context = context?.applicationContext ?: return
        showRimeProgress(R.string.pref_cloud_clipboard_test)
        executor?.execute {
            val sync = CloudClipboardSync(context)
            val result = runBlocking { sync.testConnection() }
            sync.shutdown()
            mainHandler.post {
                hideRimeProgress()
                if (!isAdded) return@post
                result.onSuccess {
                    Toast.makeText(requireContext(), R.string.pref_cloud_clipboard_test_ok, Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.pref_cloud_clipboard_test_failed,
                            error.message.orEmpty().ifBlank { error::class.java.simpleName }
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun syncUserDictWithWebDav() {
        val context = context?.applicationContext ?: return
        showRimeProgress(R.string.pref_user_dict_syncing)
        executor?.execute {
            val result = runCatching {
                UserDictWebDavSync(context).sync()
            }
            mainHandler.post {
                hideRimeProgress()
                if (!isAdded) return@post
                result.onSuccess { syncResult ->
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.pref_user_dict_sync_done,
                            syncResult.downloaded,
                            syncResult.uploaded
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.pref_user_dict_sync_failed,
                            error.message.orEmpty().ifBlank { error::class.java.simpleName }
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        hideRimeProgress()
        executor?.shutdown()
        executor = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun loadRimePreferences(showProgress: Boolean = false) {
        val context = context?.applicationContext ?: return
        val schemeSetPref = findPreference<ListPreference>("rime_scheme_set")
        val schemaPref = findPreference<ListPreference>("rime_schema")
        schemeSetPref?.isEnabled = false
        schemaPref?.isEnabled = false
        if (showProgress) {
            showRimeProgress(R.string.pref_rime_loading)
        }
        executor?.execute {
            val session = MoqiImeSession(
                guid = Mobilebridge.GUIDRime,
                androidDataDir = context.moqiAndroidDataDir().absolutePath
            )
            val schemeSets = session.schemeSets()
            val currentSchemeSet = session.currentSchemeSet()
            val schemas = session.schemaEntries()
            val currentSchemaId = session.currentSchemaId()
            session.close()
            mainHandler.post {
                if (showProgress) {
                    hideRimeProgress()
                }
                if (!isAdded) return@post
                updateSchemeSetPreference(schemeSetPref, schemeSets, currentSchemeSet)
                updateSchemaPreference(schemaPref, schemas, currentSchemaId)
            }
        }
    }

    private fun selectRimeSchemeSet(name: String) {
        val context = context?.applicationContext ?: return
        mainHandler.postDelayed({
            if (!isAdded) return@postDelayed
            showRimeProgress(R.string.pref_rime_switching_scheme_set)
            executor?.execute {
                val session = MoqiImeSession(
                    guid = Mobilebridge.GUIDRime,
                    androidDataDir = context.moqiAndroidDataDir().absolutePath
                )
                val result = session.selectSchemeSet(name)
                if (result.success) {
                    waitForRimeSchemas(session, name)
                }
                session.close()
                mainHandler.post {
                    hideRimeProgress()
                    if (!isAdded) return@post
                    if (!result.success) {
                        Toast.makeText(requireContext(), "${getString(R.string.pref_rime_switch_failed)}: ${result.error}", Toast.LENGTH_LONG).show()
                    }
                    loadRimePreferences()
                }
            }
        }, 250L)
    }

    private fun waitForRimeSchemas(session: MoqiImeSession, schemeSet: String? = null): List<RimeSchemaEntry> {
        repeat(RIME_READY_ATTEMPTS) {
            val currentSchemeSet = session.currentSchemeSet()
            val schemas = session.schemaEntries()
            val schemeSetReady = schemeSet == null || currentSchemeSet == schemeSet
            if (schemeSetReady && schemas.isNotEmpty()) {
                return schemas
            }
            Thread.sleep(RIME_READY_DELAY_MS)
        }
        return session.schemaEntries()
    }

    private fun selectRimeSchema(schemaId: String) {
        val context = context?.applicationContext ?: return
        executor?.execute {
            val session = MoqiImeSession(
                guid = Mobilebridge.GUIDRime,
                androidDataDir = context.moqiAndroidDataDir().absolutePath
            )
            val result = session.selectSchema(schemaId)
            session.close()
            mainHandler.post {
                if (!isAdded) return@post
                if (!result.success) {
                    Toast.makeText(requireContext(), "${getString(R.string.pref_rime_switch_failed)}: ${result.error}", Toast.LENGTH_LONG).show()
                }
                loadRimePreferences()
            }
        }
    }

    private fun updateSchemeSetPreference(pref: ListPreference?, schemeSets: List<String>, currentSchemeSet: String) {
        if (pref == null) return
        if (schemeSets.isEmpty()) {
            pref.summary = getString(R.string.pref_rime_empty)
            pref.isEnabled = false
            return
        }
        pref.entries = schemeSets.toTypedArray()
        pref.entryValues = schemeSets.toTypedArray()
        pref.value = currentSchemeSet.ifBlank { schemeSets.first() }
        pref.summary = pref.value
        pref.isEnabled = true
    }

    private fun updateSchemaPreference(pref: ListPreference?, schemas: List<RimeSchemaEntry>, currentSchemaId: String) {
        if (pref == null) return
        if (schemas.isEmpty()) {
            pref.summary = getString(R.string.pref_rime_empty)
            pref.isEnabled = false
            return
        }
        pref.entries = schemas.map { "${it.name} (${it.id})" }.toTypedArray()
        pref.entryValues = schemas.map { it.id }.toTypedArray()
        pref.value = currentSchemaId.ifBlank {
            schemas.firstOrNull { it.selected }?.id ?: schemas.first().id
        }
        pref.summary = schemas.firstOrNull { it.id == pref.value }?.name ?: pref.value
        pref.isEnabled = true
    }
}
package com.moqi.im.settings

import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.moqi.im.R
import com.moqi.im.data.RimeSafSync
import com.moqi.im.engine.MoqiImeSession
import com.moqi.im.engine.RimeSchemaEntry
import com.moqi.im.moqiAndroidDataDir
import com.moqi.im.theme.ThemePalette
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

    private fun applyThemeBackground() {
        val color = ThemePalette.backgroundColor(requireContext())
        view?.setBackgroundColor(color)
        listView.setBackgroundColor(color)
        activity?.findViewById<View>(R.id.settings_root)?.setBackgroundColor(color)
    }

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
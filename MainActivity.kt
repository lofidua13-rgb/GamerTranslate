package com.gamertranslate.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        val btnStart         = findViewById<Button>(R.id.btnStartService)
        val btnStop          = findViewById<Button>(R.id.btnStopService)
        val btnOverlay       = findViewById<Button>(R.id.btnOverlayPerm)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibilityPerm)
        val switchAuto       = findViewById<Switch>(R.id.switchAutoTranslate)
        val spinnerFrom      = findViewById<Spinner>(R.id.spinnerFrom)
        val spinnerTo        = findViewById<Spinner>(R.id.spinnerTo)
        val spinnerAI        = findViewById<Spinner>(R.id.spinnerAIProvider)
        val etGeminiKey      = findViewById<EditText>(R.id.etGeminiKey)
        val etOpenAIKey      = findViewById<EditText>(R.id.etOpenAIKey)
        val etClaudeKey      = findViewById<EditText>(R.id.etClaudeKey)
        val btnSaveKeys      = findViewById<Button>(R.id.btnSaveKeys)

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        // Languages
        val languages = arrayOf("Hindi","English","Spanish","Portuguese","Arabic","Russian","German","Japanese","Korean","Chinese")
        val langCodes = arrayOf("hi","en","es","pt","ar","ru","de","ja","ko","zh")
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFrom.adapter = langAdapter; spinnerTo.adapter = langAdapter
        spinnerTo.setSelection(1)

        spinnerFrom.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putString("from_lang", langCodes[pos]).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnerTo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putString("to_lang", langCodes[pos]).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val savedFrom = prefs.getString("from_lang","hi") ?: "hi"
        val savedTo   = prefs.getString("to_lang","en") ?: "en"
        spinnerFrom.setSelection(langCodes.indexOf(savedFrom).coerceAtLeast(0))
        spinnerTo.setSelection(langCodes.indexOf(savedTo).coerceAtLeast(0))

        // AI Provider
        val aiOptions = arrayOf("🆓 MyMemory (Free)","✨ Gemini AI","🤖 ChatGPT","🧠 Claude AI")
        val aiAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, aiOptions)
        aiAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAI.adapter = aiAdapter
        spinnerAI.setSelection(prefs.getInt("ai_provider", 0))
        spinnerAI.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putInt("ai_provider", pos).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Load saved keys
        etGeminiKey.setText(prefs.getString("gemini_api_key",""))
        etOpenAIKey.setText(prefs.getString("openai_api_key",""))
        etClaudeKey.setText(prefs.getString("claude_api_key",""))

        btnSaveKeys.setOnClickListener {
            prefs.edit()
                .putString("gemini_api_key", etGeminiKey.text.toString().trim())
                .putString("openai_api_key", etOpenAIKey.text.toString().trim())
                .putString("claude_api_key", etClaudeKey.text.toString().trim())
                .apply()
            Toast.makeText(this, "✅ API Keys Saved!", Toast.LENGTH_SHORT).show()
        }

        switchAuto.isChecked = prefs.getBoolean("auto_translate", true)
        switchAuto.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("auto_translate", checked).apply() }

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            ContextCompat.startForegroundService(this, Intent(this, FloatingService::class.java))
            Toast.makeText(this, "🌐 Translator bubble started!", Toast.LENGTH_SHORT).show()
            finish()
        }
        btnStop.setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, "Translator stopped", Toast.LENGTH_SHORT).show()
        }
        btnOverlay.setOnClickListener { requestOverlayPermission() }
        btnAccessibility.setOnClickListener { requestAccessibilityPermission() }
    }

    private fun checkPermissions() {
        val overlayStatus = findViewById<TextView>(R.id.tvOverlayStatus)
        val accessStatus  = findViewById<TextView>(R.id.tvAccessibilityStatus)
        if (Settings.canDrawOverlays(this)) {
            overlayStatus.text = "✅ Overlay: Granted"; overlayStatus.setTextColor(getColor(R.color.green))
        } else {
            overlayStatus.text = "❌ Overlay: Not Granted"; overlayStatus.setTextColor(getColor(R.color.red))
        }
        if (isAccessibilityEnabled()) {
            accessStatus.text = "✅ Accessibility: Granted"; accessStatus.setTextColor(getColor(R.color.green))
        } else {
            accessStatus.text = "⚠️ Accessibility: Not Granted"; accessStatus.setTextColor(getColor(R.color.orange))
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "${packageName}/${TranslateAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(service) == true
    }
    private fun requestOverlayPermission() {
        startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 1001)
    }
    private fun requestAccessibilityPermission() { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    override fun onResume() { super.onResume(); checkPermissions() }
}

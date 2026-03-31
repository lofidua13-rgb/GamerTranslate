package com.gamertranslate.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.math.abs

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var panelView: View
    private var isPanelVisible = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f
    private var isDragging = false

    enum class AIProvider { GEMINI, OPENAI, CLAUDE, MYMEMORY }

    // ─── SMART HINGLISH PROMPT ────────────────────────────────────────────────
    private fun getSmartPrompt(userText: String): String {
        return """
You are a smart bilingual assistant for Indian gamers who speak Hinglish (a mix of Hindi and English).

Your job:
- If the input is in Hinglish or Hindi → translate to clear, simple English
- If the input is in English → translate to easy Hinglish (mix of Hindi + English) so an Indian gamer can understand
- Auto-detect the language — do NOT ask the user which language it is
- Keep gaming context in mind (terms like "drop", "rush", "loot", "squad" stay as-is)
- Keep the reply SHORT and natural, like a gamer would say it
- Return ONLY the translated text, nothing else

Text to translate:
$userText
        """.trimIndent()
    }

    // BroadcastReceiver for AccessibilityService
    private val translateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra("text") ?: return
            setInputText(text)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createBubble()
        createPanel()
        val filter = IntentFilter("com.gamertranslate.TRANSLATE_TEXT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(translateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(translateReceiver, filter)
        }
    }

    private fun startForegroundService() {
        val channelId = "translator_channel"
        val channel = NotificationChannel(channelId, "Translator", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("🌐 GamerTranslate Active")
            .setContentText("Tap bubble to translate.")
            .setSmallIcon(R.drawable.ic_translate)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).build()
        startForeground(1, notification)
    }

    private fun createBubble() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_layout, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 20; params.y = 200
        windowManager.addView(bubbleView, params)

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) isDragging = true
                    if (isDragging) { params.x = initialX + dx; params.y = initialY + dy; windowManager.updateViewLayout(bubbleView, params) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!isDragging) togglePanel(); true }
                else -> false
            }
        }
        bubbleView.findViewById<View>(R.id.btnBubbleClose)?.setOnClickListener { stopSelf() }
    }

    private fun createPanel() {
        panelView = LayoutInflater.from(this).inflate(R.layout.panel_layout, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM
        panelView.visibility = View.GONE
        windowManager.addView(panelView, params)
        setupPanelUI()
    }

    private fun setupPanelUI() {
        val etInput      = panelView.findViewById<EditText>(R.id.etInput)
        val tvOutput     = panelView.findViewById<TextView>(R.id.tvOutput)
        val tvMode       = panelView.findViewById<TextView>(R.id.tvTranslateMode)
        val btnTranslate = panelView.findViewById<Button>(R.id.btnTranslate)
        val btnClose     = panelView.findViewById<Button>(R.id.btnClose)
        val btnCopy      = panelView.findViewById<Button>(R.id.btnCopy)
        val btnPaste     = panelView.findViewById<Button>(R.id.btnPaste)
        val progressBar  = panelView.findViewById<ProgressBar>(R.id.progressBar)
        val spinnerAI    = panelView.findViewById<Spinner>(R.id.spinnerAIProvider)

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // AI Provider spinner
        val aiOptions = arrayOf("🆓 MyMemory (Free)", "✨ Gemini AI", "🤖 ChatGPT", "🧠 Claude AI")
        val aiAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, aiOptions)
        aiAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAI?.adapter = aiAdapter
        spinnerAI?.setSelection(prefs.getInt("ai_provider", 0))
        spinnerAI?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putInt("ai_provider", pos).apply()
                // Show mode label only for AI providers
                tvMode?.visibility = if (pos == 0) View.GONE else View.VISIBLE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Show mode indicator
        tvMode?.text = "🔄 Auto-detect: Hinglish ↔ English"
        tvMode?.visibility = if (prefs.getInt("ai_provider", 0) == 0) View.GONE else View.VISIBLE

        btnPaste.setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            etInput.setText(text); etInput.setSelection(text.length)
        }

        val doTranslate = {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                progressBar.visibility = View.VISIBLE
                btnTranslate.isEnabled = false
                tvOutput.text = "Translating..."
                val provider = getProvider(prefs)
                serviceScope.launch {
                    val result = translateText(text, provider)
                    progressBar.visibility = View.GONE
                    btnTranslate.isEnabled = true
                    tvOutput.text = result
                }
            }
        }

        btnTranslate.setOnClickListener { doTranslate() }
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { doTranslate(); true } else false
        }
        btnCopy.setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText("translation", tvOutput.text))
            Toast.makeText(this, "✅ Copied!", Toast.LENGTH_SHORT).show()
        }
        btnClose.setOnClickListener { hidePanel() }
    }

    private fun getProvider(prefs: android.content.SharedPreferences): AIProvider {
        return when (prefs.getInt("ai_provider", 0)) {
            1 -> AIProvider.GEMINI
            2 -> AIProvider.OPENAI
            3 -> AIProvider.CLAUDE
            else -> AIProvider.MYMEMORY
        }
    }

    fun togglePanel() { if (isPanelVisible) hidePanel() else showPanel() }

    private fun showPanel() {
        panelView.visibility = View.VISIBLE; isPanelVisible = true
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (clipText.isNotBlank()) panelView.findViewById<EditText>(R.id.etInput).setText(clipText)
    }

    private fun hidePanel() { panelView.visibility = View.GONE; isPanelVisible = false }

    // ─── TRANSLATE ROUTER (no more from/to — AI auto detects) ────────────────
    private suspend fun translateText(text: String, provider: AIProvider): String {
        return when (provider) {
            AIProvider.GEMINI   -> translateWithGemini(text)
            AIProvider.OPENAI   -> translateWithOpenAI(text)
            AIProvider.CLAUDE   -> translateWithClaude(text)
            AIProvider.MYMEMORY -> translateWithMyMemory(text)
        }
    }

    // ─── MyMemory fallback (no Hinglish support, basic only) ─────────────────
    private suspend fun translateWithMyMemory(text: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(text, "UTF-8")
                val response = URL("https://api.mymemory.translated.net/get?q=$encoded&langpair=hi|en").readText()
                val translated = JSONObject(response).getJSONObject("responseData").getString("translatedText")
                if (translated.isNotBlank()) "[$translated]\n⚠️ Hinglish ke liye Gemini/ChatGPT/Claude use karo!" else "❌ Could not translate"
            } catch (e: Exception) { "⚠️ Error: ${e.message}" }
        }
    }

    // ─── Gemini AI ────────────────────────────────────────────────────────────
    private suspend fun translateWithGemini(text: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("gemini_api_key", "") ?: ""
                if (apiKey.isBlank()) return@withContext "⚠️ Gemini API key missing! Settings mein add karo."
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                val conn = url.openConnection() as HttpsURLConnection
                conn.requestMethod = "POST"; conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                val prompt = getSmartPrompt(text)
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                JSONObject(conn.inputStream.bufferedReader().readText())
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text").trim()
            } catch (e: Exception) { "⚠️ Gemini Error: ${e.message}" }
        }
    }

    // ─── ChatGPT ──────────────────────────────────────────────────────────────
    private suspend fun translateWithOpenAI(text: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("openai_api_key", "") ?: ""
                if (apiKey.isBlank()) return@withContext "⚠️ OpenAI API key missing! Settings mein add karo."
                val conn = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpsURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.doOutput = true
                val systemPrompt = """
You are a smart bilingual assistant for Indian gamers who speak Hinglish.
- If input is Hinglish/Hindi → translate to simple English
- If input is English → translate to easy Hinglish
- Gaming terms like drop, rush, loot, squad stay as-is
- Return ONLY the translated text, nothing else
                """.trimIndent()
                val body = JSONObject().put("model", "gpt-4o-mini").put("max_tokens", 300)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", text)))
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                JSONObject(conn.inputStream.bufferedReader().readText())
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
            } catch (e: Exception) { "⚠️ OpenAI Error: ${e.message}" }
        }
    }

    // ─── Claude AI ────────────────────────────────────────────────────────────
    private suspend fun translateWithClaude(text: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("claude_api_key", "") ?: ""
                if (apiKey.isBlank()) return@withContext "⚠️ Claude API key missing! Settings mein add karo."
                val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpsURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-api-key", apiKey)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.doOutput = true
                val systemPrompt = """
You are a smart bilingual assistant for Indian gamers who speak Hinglish.
- If input is Hinglish/Hindi → translate to simple English
- If input is English → translate to easy Hinglish
- Gaming terms like drop, rush, loot, squad stay as-is
- Return ONLY the translated text, nothing else
                """.trimIndent()
                val body = JSONObject()
                    .put("model", "claude-haiku-4-5-20251001")
                    .put("max_tokens", 300)
                    .put("system", systemPrompt)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                JSONObject(conn.inputStream.bufferedReader().readText())
                    .getJSONArray("content").getJSONObject(0).getString("text").trim()
            } catch (e: Exception) { "⚠️ Claude Error: ${e.message}" }
        }
    }

    fun setInputText(text: String) {
        panelView.findViewById<EditText>(R.id.etInput).setText(text)
        showPanel()
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_translate", true)) {
            val tvOutput = panelView.findViewById<TextView>(R.id.tvOutput)
            val pb = panelView.findViewById<ProgressBar>(R.id.progressBar)
            pb.visibility = View.VISIBLE; tvOutput.text = "Translating..."
            serviceScope.launch {
                val result = translateText(text, getProvider(prefs))
                pb.visibility = View.GONE; tvOutput.text = result
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try { unregisterReceiver(translateReceiver) } catch (e: Exception) {}
        if (::bubbleView.isInitialized) windowManager.removeView(bubbleView)
        if (::panelView.isInitialized) windowManager.removeView(panelView)
    }
}

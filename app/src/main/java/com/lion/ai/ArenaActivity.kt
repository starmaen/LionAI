package com.lion.ai

import android.os.Bundle
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

/**
 * النشاط الذي يستضيف الـ WebView لدردشات الويب (Arena، Gemini، DeepSeek، ChatGPT…).
 *
 * كل المنطق في [LionWebController] والواجهة في [LionWebScreen]. مهمة هذا النشاط:
 * تفعيل العرض من الحافة إلى الحافة، استعادة حالة الصفحة بعد تدوير الشاشة أو موت
 * العملية، وتمرير نتيجة منتقي الملفات، وجعل زر الرجوع يتنقّل داخل الصفحة قبل الإغلاق.
 */
class ArenaActivity : ComponentActivity() {

    private lateinit var controller: LionWebController

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        controller.onFileChooserResult(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val startUrl = intent.getStringExtra(EXTRA_URL)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_URL

        controller = LionWebController(this, startUrl)
        controller.launchFileChooser = { chooserIntent -> fileLauncher.launch(chooserIntent) }
        controller.setRestoreState(savedInstanceState)

        setContent {
            LionWebScreen(controller = controller, onClose = { finish() })
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!controller.navigateBack()) finish()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::controller.isInitialized) controller.saveState(outState)
    }

    override fun onDestroy() {
        if (this::controller.isInitialized) controller.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "ARENA_URL"
        private const val DEFAULT_URL = "https://arena.ai/text/direct?model_a=max"
    }
}

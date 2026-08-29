package dev.nytweetdeck.android.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewClientCompat
import dev.nytweetdeck.android.security.verifiedExternalHttpsUrl
import dev.nytweetdeck.android.R

class LoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var finishButton: Button
    private lateinit var status: TextView
    private lateinit var cookieManager: CookieManager
    private val cookieHandler = Handler(Looper.getMainLooper())
    private val cookieCheck = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                updateLoginState()
                cookieHandler.postDelayed(this, COOKIE_CHECK_INTERVAL_MILLIS)
            }
        }
    }
    private var rendererGone = false
    private val profileName by lazy {
        intent.getStringExtra(EXTRA_PROFILE_NAME)?.takeIf(PROFILE_PATTERN::matches)
            ?: "nytweetdeck-login"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.x_login_title)
        buildContentView()
        configureWebView()
        webView.loadUrl(LOGIN_URL)
    }

    override fun onDestroy() {
        cookieHandler.removeCallbacks(cookieCheck)
        webView.stopLoading()
        if (!rendererGone) webView.destroy()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        cookieHandler.removeCallbacks(cookieCheck)
        cookieHandler.post(cookieCheck)
    }

    override fun onPause() {
        cookieHandler.removeCallbacks(cookieCheck)
        super.onPause()
    }

    private fun buildContentView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 24, 32))
        }
        status = TextView(this).apply {
            setText(R.string.x_login_guidance)
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
        }
        webView = WebView(this)
        finishButton = Button(this).apply {
            setText(R.string.complete_x_login)
            isEnabled = false
            setOnClickListener { completeLogin() }
        }
        root.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        root.addView(finishButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        setContentView(FrameLayout(this).apply { addView(root) })
    }

    // The client below implements onRenderProcessGone; androidx.webkit lint does not
    // recognize the override on this anonymous WebViewClientCompat implementation.
    @SuppressLint("SetJavaScriptEnabled", "MissingOnRenderProcessGone")
    private fun configureWebView() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            WebViewCompat.setProfile(webView, profileName)
            cookieManager = WebViewCompat.getProfile(webView).cookieManager
        } else {
            cookieManager = CookieManager.getInstance()
        }
        cookieManager.setAcceptCookie(true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                if (!request.isForMainFrame || isTrustedXUri(uri)) return false
                verifiedExternalHttpsUrl(uri.toString())?.let { verified ->
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(verified))
                            .addCategory(Intent.CATEGORY_BROWSABLE),
                    )
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                updateLoginState()
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                finishButton.isEnabled = false
                status.setText(R.string.x_login_renderer_failed)
                rendererGone = true
                cookieHandler.removeCallbacks(cookieCheck)
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                return true
            }
        }
    }

    private fun updateLoginState() {
        if (rendererGone) return
        val cookies = cookieManager.getCookie(X_ORIGIN).orEmpty()
        val ready = XWebSessionCookies.fromHeader(cookies) != null
        finishButton.isEnabled = ready
        status.setText(if (ready) R.string.x_login_detected else R.string.x_login_guidance)
    }

    private fun completeLogin() {
        val session = XWebSessionCookies.fromHeader(cookieManager.getCookie(X_ORIGIN).orEmpty())
        if (session == null) {
            updateLoginState()
            return
        }
        cookieManager.flush()
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_NAME, profileName)
            putExtra(EXTRA_USER_ID, session.userId)
            putExtra(EXTRA_AUTH_TOKEN, session.authToken)
            putExtra(EXTRA_CSRF_TOKEN, session.csrfToken)
        })
        finish()
    }

    companion object {
        const val EXTRA_PROFILE_NAME = "dev.nytweetdeck.android.profileName"
        const val EXTRA_USER_ID = "dev.nytweetdeck.android.userId"
        const val EXTRA_AUTH_TOKEN = "dev.nytweetdeck.android.authToken"
        const val EXTRA_CSRF_TOKEN = "dev.nytweetdeck.android.csrfToken"
        private const val LOGIN_URL = "https://x.com/i/flow/login"
        private const val X_ORIGIN = "https://x.com/"
        private const val COOKIE_CHECK_INTERVAL_MILLIS = 1_000L
        private val PROFILE_PATTERN = Regex("[A-Za-z0-9_-]{1,80}")

        private fun isTrustedXUri(uri: Uri): Boolean {
            return verifiedExternalHttpsUrl(
                uri.toString(),
                setOf("x.com", "twitter.com"),
            ) != null
        }

    }
}

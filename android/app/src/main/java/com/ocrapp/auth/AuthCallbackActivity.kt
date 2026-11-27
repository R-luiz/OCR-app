package com.ocrapp.auth

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ocrapp.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives GitHub's redirect to [SIGN_IN_CALLBACK_URL], which Android hands to the app
 * once it has verified the domain's `assetlinks.json`.
 *
 * It shows nothing: it passes GitHub's answer to [ManagedSignIn], brings the app's own
 * task back to the front, and finishes. Any app can send it an intent, so what it carries
 * counts only if its `state` is the one of the sign-in in progress.
 */
@AndroidEntryPoint
class AuthCallbackActivity : ComponentActivity() {

    @Inject
    lateinit var signIn: ManagedSignIn

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated after a configuration change: the first instance already handed it on.
        val callback = intent?.data?.takeIf { savedInstanceState == null && it.isSignInCallback() }
        if (callback == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val ours = signIn.complete(
                code = callback.getQueryParameter("code"),
                state = callback.getQueryParameter("state"),
                error = callback.getQueryParameter("error"),
            )
            if (ours) {
                // Into the app's existing task when there is one, onto the MainActivity
                // already there rather than a second one.
                startActivity(
                    Intent.makeMainActivity(ComponentName(this@AuthCallbackActivity, MainActivity::class.java)).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                )
            }
            finish()
        }
    }
}

/** The intent filter admits only this address, but an explicit intent bypasses the filter. */
private fun Uri.isSignInCallback(): Boolean {
    val expected = Uri.parse(SIGN_IN_CALLBACK_URL)
    return scheme == expected.scheme && host == expected.host && path == expected.path
}

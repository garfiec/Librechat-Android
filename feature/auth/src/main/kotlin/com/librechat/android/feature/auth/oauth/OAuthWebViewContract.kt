package com.librechat.android.feature.auth.oauth

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Starts [OAuthWebViewActivity] and returns the `refreshToken` cookie value on success.
 */
class OAuthWebViewContract : ActivityResultContract<Intent, String?>() {

    override fun createIntent(context: Context, input: Intent): Intent = input

    override fun parseResult(resultCode: Int, intent: Intent?): String? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        return intent.getStringExtra(OAuthWebViewActivity.EXTRA_REFRESH_TOKEN)
    }
}

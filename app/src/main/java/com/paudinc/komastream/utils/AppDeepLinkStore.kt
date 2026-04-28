package com.paudinc.komastream.utils

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppDeepLinkStore {
    private val _malCallbackUri = MutableStateFlow<Uri?>(null)

    val malCallbackUri = _malCallbackUri.asStateFlow()

    fun postMalCallback(uri: Uri?) {
        if (uri != null) {
            _malCallbackUri.value = uri
        }
    }

    fun clearMalCallback() {
        _malCallbackUri.value = null
    }
}

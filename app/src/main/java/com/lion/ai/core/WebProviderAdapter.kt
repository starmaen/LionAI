package com.lion.ai.core

import com.lion.ai.PROVIDERS
import com.lion.ai.ProviderGroup

data class WebProvider(val id: String, val name: String, val url: String, val icon: String = "🌐")
object WebProviderAdapter {
    val providers: List<WebProvider> get() = PROVIDERS.filter { it.group == ProviderGroup.WEB }
        .map { WebProvider(it.id, it.shortName, requireNotNull(it.webviewUrl)) }
    val allWebProviders: List<WebProvider> get() = providers
}

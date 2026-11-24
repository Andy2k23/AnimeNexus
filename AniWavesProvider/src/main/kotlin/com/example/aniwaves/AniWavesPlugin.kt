package com.example.aniwaves

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniWavesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniWavesProvider())
    }
}

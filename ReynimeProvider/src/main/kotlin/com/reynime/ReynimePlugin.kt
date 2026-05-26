package com.reynime

import com.lagradost.cloudstream3.Plugin

class ReynimePlugin : Plugin() {
    override fun load() {
        registerMainAPI(ReynimeProvider())
    }
}

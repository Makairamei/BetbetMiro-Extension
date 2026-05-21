package com.istarvin

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.plugins.Plugin

const val HLS_PROXY_DEFAULT_URL = "https://hls-proxy.istarvin.uk"
const val HLS_PROXY_URL_PREF_KEY = "hls_proxy_url"
private const val RESOURCE_PACKAGE = "com.istarvin"

class ExtractorsSettingsFragment(
    plugin: Plugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layoutId = res.getIdentifier("fragment_extractors_settings", "layout", RESOURCE_PACKAGE)
        if (layoutId == 0) throw Exception("Layout fragment_extractors_settings not found")
        val view = inflater.inflate(res.getLayout(layoutId), container, false)

        val proxyInputId = res.getIdentifier("hlsProxyUrlInput", "id", RESOURCE_PACKAGE)
        val saveButtonId = res.getIdentifier("saveHlsProxyUrlButton", "id", RESOURCE_PACKAGE)
        if (proxyInputId == 0) throw Exception("View ID hlsProxyUrlInput not found")
        if (saveButtonId == 0) throw Exception("View ID saveHlsProxyUrlButton not found")

        val proxyInput = view.findViewById<EditText>(proxyInputId)
        val saveButton = view.findViewById<Button>(saveButtonId)

        proxyInput.setText(sharedPref.getString(HLS_PROXY_URL_PREF_KEY, HLS_PROXY_DEFAULT_URL) ?: HLS_PROXY_DEFAULT_URL)
        saveButton.setOnClickListener {
            sharedPref.edit { putString(HLS_PROXY_URL_PREF_KEY, proxyInput.text.toString().trim()) }
            dismiss()
        }

        return view
    }
}

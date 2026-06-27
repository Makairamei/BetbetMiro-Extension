package com.phisher98

/**
 * Compatibility shim for code that expects BuildConfig in the provider package.
 * The Android Gradle namespace is com.excloud, so the generated BuildConfig
 * lives in com.excloud and is re-exported here for ShowBox sources/settings.
 */
internal object BuildConfig {
    const val LIBRARY_PACKAGE_NAME = com.excloud.BuildConfig.LIBRARY_PACKAGE_NAME
    const val SuperToken = com.excloud.BuildConfig.SuperToken
}

package com.armsone.nasfinder.platform

import androidx.core.content.FileProvider

/**
 * Read-only URI bridge used for prepared shares, durable inbox payloads and a verified update APK.
 * The manifest path policy excludes updater partials, credentials and unrelated app files.
 */
class NasFinderShareFileProvider : FileProvider()

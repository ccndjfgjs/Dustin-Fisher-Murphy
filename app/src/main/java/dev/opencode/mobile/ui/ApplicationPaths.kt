package dev.opencode.mobile.ui

import android.content.Context
import java.io.File

object ApplicationPaths {
    fun external(context: Context): String? = try {
        context.getExternalFilesDir(null)?.absolutePath
    } catch (t: Throwable) {
        null
    }

    fun internal(context: Context): String = File(context.filesDir, "workspace").absolutePath
}

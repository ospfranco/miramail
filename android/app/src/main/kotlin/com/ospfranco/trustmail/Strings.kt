package com.ospfranco.trustmail

import android.content.Context
import androidx.annotation.StringRes

/**
 * Looks up a string resource in the user's language. Logic that builds text (the link warning, notices) takes one, so
 * it stays free of Android: the app passes the Context's, JVM tests read strings.xml (see `TestStrings`).
 * The strings themselves are generated from scripts/strings.json, shared with iOS.
 */
fun interface Strings {
    fun get(@StringRes id: Int, vararg args: Any): String
}

fun Context.strings(): Strings = Strings { id, args -> getString(id, *args) }

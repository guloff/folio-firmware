package com.rustamguloff.foliolink.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** Вкладки нижней панели (как у iOS). */
enum class Tab { HOME, STATS, LIBRARY, NOTES, MORE }

/** Экраны внутри вкладки «Ещё»; [WRAPPED] и [READWISE] открываются и с других вкладок. */
enum class Dest(val key: String) {
    SCREENSAVER("screensaver"),
    SLEEP_IMAGES("sleepImages"),
    SCREENSHOTS("screenshots"),
    FONTS("fonts"),
    DICTIONARIES("dictionaries"),
    DEVICE_SETTINGS("deviceSettings"),
    APP_SETTINGS("appSettings"),
    FIRMWARE("firmware"),
    DEVICE("device"),
    OUTBOX("outbox"),
    WRAPPED("wrapped"),
    READWISE("readwise"),
    ADD_ARTICLE("addArticle"),
    ;

    companion object {
        fun from(key: String?): Dest? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Навигация приложения: выбранная вкладка, стек экранов поверх вкладки, окно подключения.
 * Экраны получают его через [LocalRouter].
 */
class Router {
    var tab by mutableStateOf(Tab.HOME)
    /** Экраны поверх текущей вкладки, последний — сверху. */
    val stack = mutableStateListOf<Dest>()
    /** Окно «Подключить читалку». */
    var showConnect by mutableStateOf(false)

    fun select(t: Tab) {
        if (tab == t) stack.clear() else { tab = t; stack.clear() }
    }

    fun open(dest: Dest) { stack.add(dest) }

    /** Открыть экран раздела «Ещё» с любой вкладки. */
    fun openInMore(dest: Dest) {
        tab = Tab.MORE
        stack.clear()
        stack.add(dest)
    }

    /** true — было что закрывать. */
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

val LocalRouter = staticCompositionLocalOf<Router> { error("Router не задан") }

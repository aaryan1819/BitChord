package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object DesktopStatsSettings {
    private val persistence = DesktopPersistence()

    private val _webdavUrl = MutableStateFlow(persistence.webdavUrl())
    val webdavUrl: StateFlow<String> get() = _webdavUrl

    fun saveWebdav(url: String, user: String, pass: String) {
        persistence.saveWebdavUrl(url)
        persistence.saveWebdavUsername(user)
        persistence.saveWebdavPassword(pass)
        _webdavUrl.value = url
    }
    
    fun disableWebdav() {
        persistence.saveWebdavUrl("")
        persistence.saveWebdavUsername("")
        persistence.saveWebdavPassword("")
        _webdavUrl.value = ""
    }
}

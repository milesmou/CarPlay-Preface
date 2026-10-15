package com.shilapi.xcertplay

import com.ecarx.sdk.mediacenter.MediaListInfo

/** Linkco announces an empty list for its external CarPlay player; do not invent a phone playlist. */
internal fun prefaceMediaList(): Any = object : MediaListInfo() {
    override fun getMediaList(): List<Any> = emptyList()
    override fun getMediaListType() = 0
    override fun getSourceType() = 6
}

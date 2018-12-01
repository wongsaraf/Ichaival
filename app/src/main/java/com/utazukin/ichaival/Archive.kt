/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2018 Utazukin
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.utazukin.ichaival

import androidx.annotation.UiThread
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class Archive(json: JSONObject) {
    val title: String = json.getString("title")
    val id: String = json.getString("arcid")
    val tags: Map<String, List<String>>
    private val imageUrls = mutableListOf<String>()
    private var loadedUrls = false
    private val mutex: Mutex by lazy { Mutex(false) }
    val numPages: Int
        get() = imageUrls.size

    init {
        val tagString: String = json.getString("tags")
        val tagList: List<String> = tagString.split(",")
        val mutableTags = mutableMapOf<String, MutableList<String>>()
        for (tag: String in tagList) {
            val trimmed = tag.trim()
            if (trimmed.contains(":")) {
                val split = trimmed.split(":")
                if (!mutableTags.containsKey(split[0]))
                    mutableTags[split[0]] = mutableListOf()
                mutableTags[split[0]]?.add(split[1])
            }
            else if (!tag.isEmpty()) {
                if (!mutableTags.containsKey("global"))
                    mutableTags["global"] = mutableListOf()
                mutableTags["global"]?.add(trimmed)
            }
        }
        tags = mutableTags
    }

    suspend fun loadImageUrls() {
        if (loadedUrls)
            return

        mutex.withLock {
            if (loadedUrls)
                return

            val jsonPages = DatabaseReader.extractArchive(id)?.getJSONArray("pages") ?: return

            val count = jsonPages.length()
            for (i in 0..(count - 1)) {
                var path = jsonPages.getString(i)
                path = path.substring(1)
                imageUrls.add(path)
            }
            loadedUrls = true
        }
    }

    fun invalidateCache() {
        imageUrls.clear()
        loadedUrls = false
    }

    fun hasPage(page: Int) : Boolean {
        return !loadedUrls || (page >= 0 && page < imageUrls.size)
    }
    @UiThread
    suspend fun getPageImage(page: Int) : String? {
        return downloadPage(page)
    }

    private suspend fun downloadPage(page: Int) : String? {
        loadImageUrls()
        return if (page < imageUrls.size) DatabaseReader.getRawImageUrl(imageUrls[page]) else null
    }

    fun containsTag(tag: String) : Boolean {
        if (tag.contains(":")) {
            val split = tag.split(":")
            val namespace = split[0].trim()
            val normalized = split[1].trim().replace("_", " ").toLowerCase()
            val nTags = getTags(namespace)
            return nTags != null && nTags.any { it.toLowerCase().contains(normalized) }
        }
        else {
            val normalized = tag.trim().replace("_", " ").toLowerCase()
            for (pair in tags) {
                if (pair.value.any { it.toLowerCase().contains(normalized)})
                    return true
            }
        }
        return false
    }

    private fun getTags(namespace: String) : List<String>? {
        return if (tags.containsKey(namespace)) tags[namespace] else null
    }
}
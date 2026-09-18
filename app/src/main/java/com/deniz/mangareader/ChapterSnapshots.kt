package com.deniz.mangareader

import org.json.JSONArray
import org.json.JSONObject

// Optional fields in the existing v1 document: old records remain readable, in their original order.
internal object ChapterSnapshots {
    fun encode(chapters: List<Chapter>): JSONArray = JSONArray().also { result ->
        chapters.forEach { chapter ->
            result.put(JSONObject().put("id", chapter.id).put("title", chapter.title)
                .put("remoteId", chapter.remoteId).put("pageCount", chapter.pageCount)
                .put("scanlator", chapter.scanlator.orEmpty())
                .put("pages", JSONArray(chapter.pages.mapNotNull { (it as? Page.Remote)?.url })))
        }
    }
    fun decode(array: JSONArray?): List<Chapter> = if (array == null) emptyList() else List(array.length()) { index ->
        val c = array.getJSONObject(index)
        val urls = c.optJSONArray("pages") ?: JSONArray()
        Chapter(c.getString("id"), c.getString("title"), List(urls.length()) { Page.Remote(urls.getString(it)) },
            c.optInt("pageCount", urls.length()), c.optString("remoteId", c.getString("id")),
            c.optString("scanlator").takeIf { it.isNotEmpty() })
    }
}

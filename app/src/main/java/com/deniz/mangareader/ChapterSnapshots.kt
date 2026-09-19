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
                .put("pages", JSONArray(chapter.pages.mapNotNull { page ->
                    (page as? Page.Remote)?.let {
                        JSONObject().put("url", it.url).put("sourceId", it.sourceId.orEmpty())
                            .put("referrer", it.referrer.orEmpty())
                    }
                })))
        }
    }
    fun decode(array: JSONArray?): List<Chapter> = if (array == null) emptyList() else List(array.length()) { index ->
        val c = array.getJSONObject(index)
        val urls = c.optJSONArray("pages") ?: JSONArray()
        Chapter(c.getString("id"), c.getString("title"), List(urls.length()) { pageIndex ->
            val value = urls.get(pageIndex)
            if (value is JSONObject) Page.Remote(
                value.getString("url"),
                value.optString("sourceId").takeIf { it.isNotEmpty() },
                value.optString("referrer").takeIf { it.isNotEmpty() }
            )
            else Page.Remote(value.toString())
        },
            c.optInt("pageCount", urls.length()), c.optString("remoteId", c.getString("id")),
            c.optString("scanlator").takeIf { it.isNotEmpty() })
    }
}

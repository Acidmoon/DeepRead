package com.vibecoding.reader.domain.reader

/**
 * 解析「从其他应用打开」的 VIEW intent。纯逻辑，便于单测。
 */
object ExternalOpenIntent {
    const val ACTION_VIEW = "android.intent.action.VIEW"

    /**
     * @return 需要导入并打开的 data URI 字符串；不是 VIEW 或没有 data 时返回 null
     */
    fun dataUri(action: String?, dataString: String?): String? {
        if (action != ACTION_VIEW) return null
        val data = dataString?.trim().orEmpty()
        return data.takeIf { it.isNotEmpty() }
    }

    /**
     * 同一 URI 已在待处理队列中时忽略重复 offer（onCreate + onNewIntent 或重复投递）。
     * 消费之后再次出现同一 URI 仍会接受，以便用户重新打开。
     */
    fun shouldOffer(pendingUri: String?, incomingUri: String): Boolean {
        val incoming = incomingUri.trim()
        if (incoming.isEmpty()) return false
        return pendingUri != incoming
    }
}

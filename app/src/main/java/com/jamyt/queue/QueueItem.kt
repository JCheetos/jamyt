package com.jamyt.queue

import java.util.UUID

/**
 * Un elemento de la cola compartida.
 * Identidad por [itemId] (UUID), no por [videoId], para que dos personas puedan
 * añadir el mismo video sin colisionar.
 */
data class QueueItem(
    val itemId: String = UUID.randomUUID().toString(),
    val videoId: String,
    val title: String,
    val addedBy: String,
    val addedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun fromYoutubeUrl(rawUrl: String, title: String, addedBy: String): QueueItem? {
            val id = extractYoutubeId(rawUrl) ?: return null
            return QueueItem(videoId = id, title = title.ifBlank { "YouTube $id" }, addedBy = addedBy)
        }

        /**
         * Extrae el video ID de URLs tipo:
         *   https://www.youtube.com/watch?v=ID
         *   https://youtu.be/ID
         *   https://www.youtube.com/shorts/ID
         *   https://m.youtube.com/watch?v=ID&t=42
         */
        fun extractYoutubeId(url: String): String? {
            val patterns = listOf(
                Regex("""(?:youtube\.com/watch\?v=|youtu\.be/|youtube\.com/shorts/|youtube\.com/embed/)([\w-]{11})"""),
            )
            for (p in patterns) {
                p.find(url)?.groupValues?.getOrNull(1)?.let { return it }
            }
            // Si ya parece un ID puro (11 chars alfanuméricos)
            if (url.length == 11 && url.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                return url
            }
            return null
        }
    }
}
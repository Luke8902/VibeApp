package com.example.pcmonitor.lyrics

data class LyricLine(val timeMs: Long, val text: String)

object LrcParser {
    private val tag = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]")

    fun parse(lrc: String): List<LyricLine> = lrc.lineSequence().flatMap { line ->
        val matches = tag.findAll(line).toList()
        if (matches.isEmpty()) emptySequence() else {
            val text = line.substring(matches.last().range.last + 1).trim()
            matches.asSequence().map { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                LyricLine((minutes * 60 + seconds) * 1000 + fraction, text)
            }
        }
    }.sortedBy { it.timeMs }.toList()
}

package com.tencent.kuikly.a2ui.adapter

/** Formats epoch milliseconds in UTC without platform date-formatting dependencies. */
internal fun formatActionTimestamp(epochMillis: Long): String {
    val seconds = epochMillis / 1000 - if (epochMillis % 1000 < 0) 1 else 0
    val millis = ((epochMillis % 1000 + 1000) % 1000).toInt()
    val epochDay = seconds / 86400 - if (seconds % 86400 < 0) 1 else 0
    val time = ((seconds % 86400 + 86400) % 86400).toInt()
    val days = epochDay + 719468
    val era = (if (days >= 0) days else days - 146096) / 146097
    val dayOfEra = days - era * 146097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
    var year = yearOfEra + era * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPrime = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthPrime + 2) / 5 + 1
    val month = monthPrime + if (monthPrime < 10) 3 else -9
    if (month <= 2) year++
    fun padded(value: Long, width: Int) = value.toString().padStart(width, '0')
    val yearText = when {
        year < 0 -> "-" + padded(-year, 6)
        year > 9999 -> "+" + padded(year, 6)
        else -> padded(year, 4)
    }
    return "$yearText-${padded(month, 2)}-${padded(day, 2)}" +
        "T${padded((time / 3600).toLong(), 2)}:${padded((time / 60 % 60).toLong(), 2)}:" +
        "${padded((time % 60).toLong(), 2)}.${padded(millis.toLong(), 3)}Z"
}

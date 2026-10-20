package dev.rime.ui.controlcenter

/** Shared editable presentation layout. Tile lifecycle/availability remains host-owned. */
data class ControlLayoutItem(val id: String, val columns: Int = 1, val rows: Int = 1)
data class ControlPlacement(val item: ControlLayoutItem, val column: Int, val row: Int)

object ControlCenterLayout {
    val defaults = listOf(
        ControlLayoutItem("connectivity", 2, 2), ControlLayoutItem("media", 2, 2),
        ControlLayoutItem("rotation"), ControlLayoutItem("silent"),
        ControlLayoutItem("brightness", 1, 2), ControlLayoutItem("volume", 1, 2),
        ControlLayoutItem("focus", 2, 1), ControlLayoutItem("flashlight"),
        ControlLayoutItem("timer"), ControlLayoutItem("calculator"), ControlLayoutItem("camera"),
    )
    val availableIds = defaults.map { it.id }.toSet()
    fun validate(items: List<ControlLayoutItem>) = items.filter { it.id in availableIds }.distinctBy { it.id }
        .map {
            val group = it.id == "connectivity" || it.id == "media"
            val slider = it.id == "brightness" || it.id == "volume"
            it.copy(columns = it.columns.coerceIn(if (group) 2 else 1, 4), rows = it.rows.coerceIn(if (group || slider) 2 else 1, 3))
        }
    fun pack(items: List<ControlLayoutItem>): List<ControlPlacement> {
        val occupied = mutableSetOf<Pair<Int, Int>>()
        return validate(items).map { item ->
            var row = 0; var column = 0
            while (true) {
                val fits = column + item.columns <= 4 && (row until row + item.rows).all { y -> (column until column + item.columns).all { x -> (x to y) !in occupied } }
                if (fits) break
                column++
                if (column >= 4) { column = 0; row++ }
            }
            for (y in row until row + item.rows) for (x in column until column + item.columns) occupied.add(x to y)
            ControlPlacement(item, column, row)
        }
    }
    fun move(items: List<ControlLayoutItem>, id: String, beforeId: String): List<ControlLayoutItem> {
        val item = items.firstOrNull { it.id == id } ?: return items
        if (id == beforeId) return items
        val remaining = items.filterNot { it.id == id }.toMutableList()
        val index = remaining.indexOfFirst { it.id == beforeId }.let { if (it < 0) remaining.size else it }
        remaining.add(index, item)
        return remaining
    }
    fun resized(item: ControlLayoutItem): ControlLayoutItem = when {
        item.id == "connectivity" || item.id == "media" -> item.copy(columns = if (item.columns == 2) 4 else 2, rows = 2)
        item.id == "brightness" || item.id == "volume" -> item.copy(columns = if (item.columns == 1) 2 else 1, rows = 2)
        item.columns == 1 && item.rows == 1 -> item.copy(columns = 2, rows = 1)
        item.columns == 2 && item.rows == 1 -> item.copy(columns = 2, rows = 2)
        else -> item.copy(columns = 1, rows = 1)
    }
}

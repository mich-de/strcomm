package com.s4me.tv.client.ui.lists

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.s4me.tv.engine.RatingStore
import com.s4me.tv.engine.StreamItem
import androidx.compose.ui.unit.dp

/** How a personal list is ordered. The stores hand items back most-recently-added first, which is
 *  [RECENT]; the rest re-order that in memory — a list is a few dozen titles, so no query is needed. */
enum class ListSort(val label: String) {
  RECENT("Recenti"),
  TITLE("A–Z"),
  YEAR("Anno"),
  LIKED("Piaciuti"),
}

fun sortItems(items: List<StreamItem>, sort: ListSort, ratings: RatingStore): List<StreamItem> =
  when (sort) {
    ListSort.RECENT -> items
    ListSort.TITLE -> items.sortedBy { it.title.lowercase() }
    ListSort.YEAR -> items.sortedByDescending { it.year?.toIntOrNull() ?: -1 }
    // sortedByDescending is stable, so within a rating the most recently added still comes first.
    ListSort.LIKED -> items.sortedByDescending { ratings.get(it.url) }
  }

@Composable
fun SortChips(selected: ListSort, onSelect: (ListSort) -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier.horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    ListSort.entries.forEach { option ->
      FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(option.label) })
    }
  }
}

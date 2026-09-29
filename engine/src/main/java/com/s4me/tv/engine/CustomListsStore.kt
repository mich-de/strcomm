package com.s4me.tv.engine

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private const val PREFS_NAME = "custom_lists"
private const val KEY_LISTS = "lists"
private const val KEY_ITEMS = "items"
private const val MAX_LISTS = 30
private const val MAX_ITEMS_PER_LIST = 200

@Serializable data class ListDef(val id: String, val name: String, val createdAt: Long)

@Serializable private data class ListEntry(val item: StreamItem, val addedAt: Long)

/**
 * User-created named lists ALONGSIDE the single default "La mia lista" ([WatchlistStore], left
 * untouched — its own toggle button on Home/Detail keeps working exactly as it always has). This
 * is a separate, additive concern: pick any number of these from an "Aggiungi a una lista" sheet,
 * manage them from their own screen. Two flat maps in one prefs file — [KEY_LISTS] (list
 * definitions) and [KEY_ITEMS] (listId -> its entries) — rather than one nested structure, so
 * renaming/deleting a list is a one-key write without touching its (possibly large) item map.
 */
class CustomListsStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  fun lists(): List<ListDef> = loadLists().sortedBy { it.createdAt }

  fun createList(name: String): ListDef? {
    val trimmed = name.trim().takeIf { it.isNotBlank() } ?: return null
    val current = loadLists()
    if (current.size >= MAX_LISTS) return null
    val def = ListDef(id = UUID.randomUUID().toString(), name = trimmed, createdAt = System.currentTimeMillis())
    persistLists(current + def)
    return def
  }

  fun renameList(id: String, name: String) {
    val trimmed = name.trim().takeIf { it.isNotBlank() } ?: return
    persistLists(loadLists().map { if (it.id == id) it.copy(name = trimmed) else it })
  }

  fun deleteList(id: String) {
    persistLists(loadLists().filterNot { it.id == id })
    val allItems = loadAllItems().toMutableMap()
    allItems.remove(id)
    persistAllItems(allItems)
  }

  fun itemsIn(listId: String): List<StreamItem> =
    loadAllItems()[listId]?.values?.sortedByDescending { it.addedAt }?.map { it.item }.orEmpty()

  fun contains(listId: String, contentId: String): Boolean = loadAllItems()[listId]?.containsKey(contentId) == true

  /** Every custom list [contentId] currently belongs to — drives the checkmarks in the "Aggiungi a
   *  una lista" sheet. */
  fun listsContaining(contentId: String): Set<String> = loadAllItems().filterValues { it.containsKey(contentId) }.keys

  fun addTo(listId: String, item: StreamItem) {
    val id = item.url.takeIf { it.isNotBlank() } ?: return
    val all = loadAllItems().toMutableMap()
    val listMap = (all[listId] ?: emptyMap()).toMutableMap()
    if (listMap.size >= MAX_ITEMS_PER_LIST && !listMap.containsKey(id)) return
    listMap[id] = ListEntry(item = item.copy(progress = null), addedAt = System.currentTimeMillis())
    all[listId] = listMap
    persistAllItems(all)
  }

  fun removeFrom(listId: String, contentId: String) {
    val all = loadAllItems().toMutableMap()
    val listMap = (all[listId] ?: return).toMutableMap()
    if (listMap.remove(contentId) != null) {
      all[listId] = listMap
      persistAllItems(all)
    }
  }

  private fun loadLists(): List<ListDef> {
    val raw = prefs.getString(KEY_LISTS, null) ?: return emptyList()
    return runCatching { json.decodeFromString<List<ListDef>>(raw) }.getOrDefault(emptyList())
  }

  private fun persistLists(lists: List<ListDef>) {
    prefs.edit().putString(KEY_LISTS, json.encodeToString(lists)).apply()
  }

  private fun loadAllItems(): Map<String, Map<String, ListEntry>> {
    val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyMap()
    return runCatching { json.decodeFromString<Map<String, Map<String, ListEntry>>>(raw) }.getOrDefault(emptyMap())
  }

  private fun persistAllItems(all: Map<String, Map<String, ListEntry>>) {
    prefs.edit().putString(KEY_ITEMS, json.encodeToString(all)).apply()
  }
}

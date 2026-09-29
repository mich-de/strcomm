package com.s4me.tv.engine

import com.s4me.tv.engine.channels.StreamingCommunityChannel

object ChannelRegistry {
  /** Every source this app can browse. Add new Channel implementations here as they're ported. */
  val all: List<Channel> = listOf(StreamingCommunityChannel())

  fun byId(id: String): Channel? = all.firstOrNull { it.id == id }
}

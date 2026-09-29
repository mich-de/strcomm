package com.s4me.tv.client.ui.browse

/**
 * Curated Academy Award Best Picture data, copied from the TV app. The source site carries no
 * award metadata, so "premi Oscar" can only be a hand-kept list resolved through the site's own
 * search. Titles are the site-searchable form (original where the site keeps it, Italian retitle
 * where it renames) — validated against the live search.
 */
internal val BEST_PICTURE_WINNERS: List<Pair<Int, String>> =
  listOf(
    2025 to "Una battaglia dopo l’altra",
    2024 to "Anora",
    2023 to "Oppenheimer",
    2022 to "Everything Everywhere All at Once",
    2021 to "CODA",
    2020 to "Nomadland",
    2019 to "Parasite",
    2018 to "Green Book",
    2017 to "The Shape of Water",
    2016 to "Moonlight",
    2015 to "Spotlight",
    2014 to "Birdman",
    2013 to "12 anni schiavo",
    2012 to "Argo",
    2010 to "Il discorso del re",
    2009 to "The Hurt Locker",
    2008 to "The Millionaire",
    2007 to "Non è un paese per vecchi",
    2006 to "The Departed",
    2005 to "Crash - Contatto fisico",
    2004 to "Million Dollar Baby",
    2003 to "Il Signore degli Anelli - Il ritorno del re",
    2002 to "Chicago",
    2001 to "A Beautiful Mind",
    2000 to "Gladiator",
    1999 to "American Beauty",
    1998 to "Shakespeare in Love",
    1997 to "Titanic",
    1996 to "Il paziente inglese",
    1995 to "Braveheart",
    1994 to "Forrest Gump",
    1993 to "La Lista di Schindler",
    1992 to "Gli spietati",
    1991 to "Il silenzio degli innocenti",
    1990 to "Balla coi lupi",
  )

internal val BEST_PICTURE_NOMINEES: Map<Int, List<String>> =
  mapOf(
    2025 to listOf("Bugonia", "F1", "Frankenstein", "Hamnet", "Marty Supreme", "L'agente segreto", "Sentimental Value", "I peccatori", "Train Dreams"),
    2024 to listOf("The Brutalist", "A Complete Unknown", "Conclave", "Dune - Parte due", "Emilia Pérez", "Io sono ancora qui", "I ragazzi della Nickel", "The Substance", "Wicked"),
    2023 to listOf("American Fiction", "Anatomia di una caduta", "Barbie", "The Holdovers", "Killers of the Flower Moon", "Maestro", "Past Lives", "Povere creature!", "La zona d'interesse"),
    2022 to listOf("Niente di nuovo sul fronte occidentale", "Avatar - La via dell'acqua", "Gli spiriti dell’isola", "Elvis", "The Fabelmans", "Tár", "Top Gun: Maverick", "Triangle of Sadness", "Women Talking"),
    2021 to listOf("Belfast", "Don't Look Up", "Drive My Car", "Dune", "King Richard", "Licorice Pizza", "Nightmare Alley", "Il potere del cane", "West Side Story"),
    2020 to listOf("The Father", "Judas and the Black Messiah", "Mank", "Minari", "Una donna promettente", "Sound of Metal", "Il processo ai Chicago 7"),
    2019 to listOf("Le Mans '66 - La grande sfida", "The Irishman", "Jojo Rabbit", "Joker", "Piccole donne", "Storia di un matrimonio", "1917", "C'era una volta a… Hollywood"),
    2018 to listOf("Black Panther", "BlacKkKlansman", "Bohemian Rhapsody", "La favorita", "A Star Is Born", "Vice"),
    2017 to listOf("Chiamami col tuo nome", "L'ora più buia", "Dunkirk", "Get Out", "Lady Bird", "Il filo nascosto", "The Post", "Tre manifesti a Ebbing, Missouri"),
    2016 to listOf("Arrival", "Barriere", "Hacksaw Ridge", "Hell or High Water", "Il diritto di contare", "La La Land", "Lion", "Manchester by the Sea"),
    2015 to listOf("La grande scommessa", "Il ponte delle spie", "Brooklyn", "Mad Max: Fury Road", "The Martian", "Revenant - Redivivo", "Room"),
  )

internal data class OscarEntry(val year: Int, val title: String, val winner: Boolean = false)

internal val OSCAR_WINNER_ENTRIES: List<OscarEntry> =
  BEST_PICTURE_WINNERS.map { (year, title) -> OscarEntry(year, title, winner = true) }

/** Newest year first; within a year the winner leads, then the nominees in list order. */
internal val OSCAR_NOMINEE_ENTRIES: List<OscarEntry> =
  BEST_PICTURE_NOMINEES.keys.sortedDescending().flatMap { year ->
    val winner = BEST_PICTURE_WINNERS.firstOrNull { it.first == year }?.second
    listOfNotNull(winner?.let { OscarEntry(year, it, winner = true) }) +
      BEST_PICTURE_NOMINEES.getValue(year).map { OscarEntry(year, it) }
  }

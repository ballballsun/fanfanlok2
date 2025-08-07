package com.example.fanfanlok

import android.util.Log
import org.opencv.core.Rect

/**
 * MatchLogic class manages the game state of revealed cards and decides next moves for matching.
 */
class MatchLogic {

    // Maps card template index to a list of positions where this card has been seen
    private val revealedCards = mutableMapOf<Int, MutableList<Rect>>()

    /**
     * Call this when a new card is revealed.
     * Stores the card template index and the card location on screen.
     */
    fun addRevealedCard(templateIndex: Int, location: Rect) {
        val locations = revealedCards.getOrPut(templateIndex) { mutableListOf() }
        // Avoid duplicate storage of the same card position
        if (locations.none { it == location }) {
            locations.add(location)
            Log.d("MatchLogic", "Card added: index=$templateIndex, location=$location")
        }
    }

    /**
     * Find the next pair of cards to match.
     * Returns a pair of card locations if a match is found, otherwise null.
     */
    fun findNextMatch(): Pair<Rect, Rect>? {
        for ((templateIndex, locations) in revealedCards) {
            if (locations.size >= 2) {
                // Match found, remove these matched cards from tracking
                val first = locations.removeAt(0)
                val second = locations.removeAt(0)
                if (locations.isEmpty()) {
                    revealedCards.remove(templateIndex)
                }
                Log.d("MatchLogic", "Match found for card index $templateIndex")
                return Pair(first, second)
            }
        }
        return null
    }

    /**
     * Clear all known cards, useful to reset the game state.
     */
    fun reset() {
        revealedCards.clear()
        Log.d("MatchLogic", "MatchLogic reset")
    }
}

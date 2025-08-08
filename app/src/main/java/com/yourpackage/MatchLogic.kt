package com.example.fanfanlok

import android.util.Log
import org.opencv.core.Rect

/**
 * match logic that handles card states, memory tracking, and optimal move calculation
 */
class MatchLogic {

    companion object {
        private const val TAG = "MatchLogic"
    }

    // Card state tracking
    private val cardMemory = mutableMapOf<Rect, CardState>()
    private val revealedPairs = mutableMapOf<Int, MutableList<Rect>>()
    private var lastFlippedCard: Rect? = null
    private var moveCount = 0
    private var matchCount = 0

    /**
     * Update game state based on detected cards
     */
    fun updateGameState(detectionResult: CardRecognizer.CardDetectionResult): GameStateUpdate {
        val updates = mutableListOf<StateChange>()
        val newlyRevealed = mutableListOf<CardRecognizer.DetectedCard>()
        val newlyHidden = mutableListOf<Rect>()

        // Process each detected card
        detectionResult.cards.forEach { card ->
            val previousState = cardMemory[card.position]
            val newState = CardState(
                templateIndex = card.templateIndex,
                templateName = card.templateName,
                isFaceUp = card.isFaceUp,
                confidence = card.confidence,
                lastSeen = System.currentTimeMillis()
            )

            // Check for state changes
            when {
                previousState == null -> {
                    // New card discovered
                    cardMemory[card.position] = newState
                    updates.add(StateChange.CARD_DISCOVERED(card.position, newState))
                    
                    if (card.isFaceUp) {
                        newlyRevealed.add(card)
                        addToRevealedPairs(card.templateIndex, card.position)
                    }
                }
                
                previousState.isFaceUp != card.isFaceUp -> {
                    // Card flipped
                    cardMemory[card.position] = newState
                    
                    if (card.isFaceUp) {
                        newlyRevealed.add(card)
                        addToRevealedPairs(card.templateIndex, card.position)
                        updates.add(StateChange.CARD_FLIPPED_UP(card.position, newState))
                    } else {
                        newlyHidden.add(card.position)
                        removeFromRevealedPairs(previousState.templateIndex, card.position)
                        updates.add(StateChange.CARD_FLIPPED_DOWN(card.position))
                    }
                }
                
                else -> {
                    // Update last seen time
                    cardMemory[card.position] = newState.copy(lastSeen = System.currentTimeMillis())
                }
            }
        }

        return GameStateUpdate(
            stateChanges = updates,
            newlyRevealed = newlyRevealed,
            newlyHidden = newlyHidden,
            totalCards = cardMemory.size,
            revealedCards = getCurrentlyRevealed(),
            availableMatches = findAvailableMatches()
        )
    }

    /**
     * Calculate the next optimal move
     */
    fun calculateNextMove(): NextMove? {
        val availableMatches = findAvailableMatches()
        
        return when {
            // If we have a guaranteed match, take it
            availableMatches.isNotEmpty() -> {
                val match = availableMatches.first()
                NextMove.MAKE_MATCH(match.first, match.second, match.first)
            }
            
            // If only one card is face up, flip another strategic card
            getCurrentlyRevealed().size == 1 -> {
                val revealedCard = getCurrentlyRevealed().first()
                val strategicMove = findStrategicMove(revealedCard)
                strategicMove?.let { 
                    NextMove.FLIP_CARD(it, "Strategic flip to find match for revealed card")
                }
            }
            
            // If no cards are face up, start with a random unrevealed card
            getCurrentlyRevealed().isEmpty() -> {
                val unrevealedCard = findUnrevealedCard()
                unrevealedCard?.let {
                    NextMove.FLIP_CARD(it, "Initial card flip")
                }
            }
            
            // Multiple cards revealed but no matches - this shouldn't happen in a memory game
            else -> {
                Log.w(TAG, "Unexpected game state: ${getCurrentlyRevealed().size} cards revealed but no matches")
                null
            }
        }
    }

    /**
     * Record that a match was made (for tracking progress)
     */
    fun recordMatch(card1: Rect, card2: Rect) {
        matchCount++
        moveCount++
        
        // Remove matched cards from memory (they're no longer on the board)
        cardMemory.remove(card1)
        cardMemory.remove(card2)
        
        Log.d(TAG, "Match recorded: $matchCount matches made, $moveCount total moves")
    }

    /**
     * Record that a move was made
     */
    fun recordMove() {
        moveCount++
        Log.d(TAG, "Move recorded: $moveCount total moves")
    }

    /**
     * Check if the game is complete
     */
    fun isGameComplete(): Boolean {
        val remainingCards = cardMemory.values.count { it.templateIndex >= 0 } // Exclude face-down cards
        return remainingCards == 0
    }

    /**
     * Get current game statistics
     */
    fun getGameStats(): GameStats {
        return GameStats(
            totalMoves = moveCount,
            matchesMade = matchCount,
            cardsRemaining = cardMemory.size,
            revealedCount = getCurrentlyRevealed().size,
            knownCardTypes = revealedPairs.keys.size
        )
    }

    /**
     * Reset the game state
     */
    fun reset() {
        cardMemory.clear()
        revealedPairs.clear()
        lastFlippedCard = null
        moveCount = 0
        matchCount = 0
        Log.d(TAG, "Match logic reset")
    }

    private fun addToRevealedPairs(templateIndex: Int, position: Rect) {
        if (templateIndex >= 0) { // Only track face-up cards
            val positions = revealedPairs.getOrPut(templateIndex) { mutableListOf() }
            if (!positions.contains(position)) {
                positions.add(position)
            }
        }
    }

    private fun removeFromRevealedPairs(templateIndex: Int, position: Rect) {
        revealedPairs[templateIndex]?.remove(position)
        if (revealedPairs[templateIndex]?.isEmpty() == true) {
            revealedPairs.remove(templateIndex)
        }
    }

    private fun getCurrentlyRevealed(): List<CardRecognizer.DetectedCard> {
        return cardMemory.entries
            .filter { it.value.isFaceUp && it.value.templateIndex >= 0 }
            .map { (position, state) ->
                CardRecognizer.DetectedCard(
                    templateIndex = state.templateIndex,
                    templateName = state.templateName,
                    position = position,
                    isFaceUp = true,
                    confidence = state.confidence
                )
            }
    }

    private fun findAvailableMatches(): List<Triple<Rect, Rect, Int>> {
        val matches = mutableListOf<Triple<Rect, Rect, Int>>()
        
        revealedPairs.forEach { (templateIndex, positions) ->
            if (positions.size >= 2) {
                // We have at least 2 cards of the same type revealed
                for (i in 0 until positions.size - 1) {
                    for (j in i + 1 until positions.size) {
                        matches.add(Triple(positions[i], positions[j], templateIndex))
                    }
                }
            }
        }
        
        return matches
    }

    private fun findStrategicMove(revealedCard: CardRecognizer.DetectedCard): Rect? {
        val revealedTemplateIndex = revealedCard.templateIndex
        
        // Look for cards we know have the same template but are currently face-down
        cardMemory.entries.find { (position, state) ->
            !state.isFaceUp && 
            state.templateIndex == revealedTemplateIndex &&
            position != revealedCard.position
        }?.let { return it.key }
        
        // If no strategic move found, pick any face-down card we haven't seen
        return findUnrevealedCard()
    }

    private fun findUnrevealedCard(): Rect? {
        return cardMemory.entries
            .find { (_, state) -> !state.isFaceUp }
            ?.key
    }

    // Data classes
    data class CardState(
        val templateIndex: Int,
        val templateName: String,
        val isFaceUp: Boolean,
        val confidence: Double,
        val lastSeen: Long
    )

    data class GameStateUpdate(
        val stateChanges: List<StateChange>,
        val newlyRevealed: List<CardRecognizer.DetectedCard>,
        val newlyHidden: List<Rect>,
        val totalCards: Int,
        val revealedCards: List<CardRecognizer.DetectedCard>,
        val availableMatches: List<Triple<Rect, Rect, Int>>
    )

    sealed class StateChange {
        data class CARD_DISCOVERED(val position: Rect, val state: CardState) : StateChange()
        data class CARD_FLIPPED_UP(val position: Rect, val state: CardState) : StateChange()
        data class CARD_FLIPPED_DOWN(val position: Rect) : StateChange()
    }

    sealed class NextMove {
        data class MAKE_MATCH(val card1: Rect, val card2: Rect, val templateIndex: Int) : NextMove()
        data class FLIP_CARD(val position: Rect, val reason: String) : NextMove()
    }

    data class GameStats(
        val totalMoves: Int,
        val matchesMade: Int,
        val cardsRemaining: Int,
        val revealedCount: Int,
        val knownCardTypes: Int
    )
}
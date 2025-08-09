package com.example.fanfanlok

import android.os.Parcel
import android.os.Parcelable
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

        Log.d(TAG, "Updating game state with ${detectionResult.cards.size} detected cards")

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
                    Log.d(TAG, "New card discovered at (${card.position.x}, ${card.position.y}): ${card.templateName}, faceUp=${card.isFaceUp}")

                    if (card.isFaceUp && card.templateIndex >= 0) {
                        newlyRevealed.add(card)
                        addToRevealedPairs(card.templateIndex, card.position)
                    }
                }

                previousState.isFaceUp != card.isFaceUp -> {
                    // Card flipped
                    cardMemory[card.position] = newState
                    Log.d(TAG, "Card state changed at (${card.position.x}, ${card.position.y}): ${previousState.isFaceUp} -> ${card.isFaceUp}")

                    if (card.isFaceUp && card.templateIndex >= 0) {
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

        val gameUpdate = GameStateUpdate(
            stateChanges = updates,
            newlyRevealed = newlyRevealed,
            newlyHidden = newlyHidden,
            totalCards = cardMemory.size,
            revealedCards = getCurrentlyRevealed(),
            availableMatches = findAvailableMatches()
        )

        Log.d(TAG, "Game state updated: ${gameUpdate.totalCards} total cards, ${gameUpdate.revealedCards.size} revealed, ${gameUpdate.availableMatches.size} matches available")

        return gameUpdate
    }

    /**
     * Calculate the next optimal move
     */
    fun calculateNextMove(): NextMove? {
        val availableMatches = findAvailableMatches()
        val currentlyRevealed = getCurrentlyRevealed()

        Log.d(TAG, "Calculating next move: ${availableMatches.size} matches available, ${currentlyRevealed.size} cards revealed")

        return when {
            // If we have a guaranteed match, take it
            availableMatches.isNotEmpty() -> {
                val match = availableMatches.first()
                Log.d(TAG, "Found available match for template ${match.third}")
                NextMove.MAKE_MATCH(match.first, match.second, match.third)
            }

            // If only one card is face up, flip another strategic card
            currentlyRevealed.size == 1 -> {
                val revealedCard = currentlyRevealed.first()
                val strategicMove = findStrategicMove(revealedCard)
                strategicMove?.let {
                    Log.d(TAG, "Strategic move: flipping card to find match for ${revealedCard.templateName}")
                    NextMove.FLIP_CARD(it, "Strategic flip to find match for revealed card")
                } ?: run {
                    // If no strategic move, flip any face-down card
                    val randomCard = findUnrevealedCard()
                    randomCard?.let {
                        Log.d(TAG, "No strategic move found, flipping random card")
                        NextMove.FLIP_CARD(it, "Random flip to continue game")
                    }
                }
            }

            // If no cards are face up, start with a random unrevealed card
            currentlyRevealed.isEmpty() -> {
                val unrevealedCard = findUnrevealedCard()
                unrevealedCard?.let {
                    Log.d(TAG, "No cards revealed, making initial flip")
                    NextMove.FLIP_CARD(it, "Initial card flip")
                } ?: run {
                    Log.w(TAG, "No unrevealed cards found!")
                    null
                }
            }

            // Multiple cards revealed but no matches - wait for cards to flip back down
            else -> {
                Log.d(TAG, "Multiple cards revealed (${currentlyRevealed.size}) but no matches - waiting...")
                null // Wait for the cards to flip back down
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
        val card1State = cardMemory.remove(card1)
        val card2State = cardMemory.remove(card2)

        // Also remove from revealed pairs
        card1State?.let { removeFromRevealedPairs(it.templateIndex, card1) }
        card2State?.let { removeFromRevealedPairs(it.templateIndex, card2) }

        Log.d(TAG, "Match recorded: $matchCount matches made, $moveCount total moves, ${cardMemory.size} cards remaining")
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
        // Count cards that are not face-down card backs
        val remainingCards = cardMemory.values.count { it.templateIndex >= 0 }
        Log.d(TAG, "Game complete check: $remainingCards cards remaining")
        return remainingCards == 0
    }

    /**
     * Get current game statistics
     */
    fun getGameStats(): GameStats {
        val revealedCount = getCurrentlyRevealed().size
        return GameStats(
            totalMoves = moveCount,
            matchesMade = matchCount,
            cardsRemaining = cardMemory.size,
            revealedCount = revealedCount,
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
                Log.d(TAG, "Added card to revealed pairs: template $templateIndex, total positions: ${positions.size}")
            }
        }
    }

    private fun removeFromRevealedPairs(templateIndex: Int, position: Rect) {
        revealedPairs[templateIndex]?.remove(position)
        if (revealedPairs[templateIndex]?.isEmpty() == true) {
            revealedPairs.remove(templateIndex)
        }
        Log.d(TAG, "Removed card from revealed pairs: template $templateIndex")
    }

    private fun getCurrentlyRevealed(): List<CardRecognizer.DetectedCard> {
        val revealed = cardMemory.entries
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

        Log.d(TAG, "Currently revealed cards: ${revealed.size}")
        return revealed
    }

    private fun findAvailableMatches(): List<Triple<Rect, Rect, Int>> {
        val matches = mutableListOf<Triple<Rect, Rect, Int>>()

        revealedPairs.forEach { (templateIndex, positions) ->
            if (positions.size >= 2) {
                // We have at least 2 cards of the same type revealed
                for (i in 0 until positions.size - 1) {
                    for (j in i + 1 until positions.size) {
                        matches.add(Triple(positions[i], positions[j], templateIndex))
                        Log.d(TAG, "Found potential match for template $templateIndex")
                    }
                }
            }
        }

        return matches
    }

    private fun findStrategicMove(revealedCard: CardRecognizer.DetectedCard): Rect? {
        val revealedTemplateIndex = revealedCard.templateIndex

        // Look for cards we know have the same template but are currently face-down
        val strategicCard = cardMemory.entries.find { (position, state) ->
            !state.isFaceUp &&
                    state.templateIndex == revealedTemplateIndex &&
                    position != revealedCard.position
        }

        strategicCard?.let {
            Log.d(TAG, "Found strategic move: matching card for template $revealedTemplateIndex")
            return it.key
        }

        // If no strategic move found, pick any face-down card we haven't seen
        return findUnrevealedCard()
    }

    private fun findUnrevealedCard(): Rect? {
        // First, try to find a card that's face-down (card back)
        val faceDownCard = cardMemory.entries
            .find { (_, state) -> !state.isFaceUp }
            ?.key

        if (faceDownCard != null) {
            Log.d(TAG, "Found face-down card for flipping")
            return faceDownCard
        }

        Log.w(TAG, "No face-down cards found in memory")
        return null
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
    ) : Parcelable {
        constructor(parcel: Parcel) : this(
            parcel.readInt(),
            parcel.readInt(),
            parcel.readInt(),
            parcel.readInt(),
            parcel.readInt()
        )

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(totalMoves)
            parcel.writeInt(matchesMade)
            parcel.writeInt(cardsRemaining)
            parcel.writeInt(revealedCount)
            parcel.writeInt(knownCardTypes)
        }

        override fun describeContents(): Int {
            return 0
        }

        companion object CREATOR : Parcelable.Creator<GameStats> {
            override fun createFromParcel(parcel: Parcel): GameStats {
                return GameStats(parcel)
            }

            override fun newArray(size: Int): Array<GameStats?> {
                return arrayOfNulls(size)
            }
        }
    }
}
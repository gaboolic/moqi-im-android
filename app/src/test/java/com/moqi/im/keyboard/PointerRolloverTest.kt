package com.moqi.im.keyboard

import org.junit.Assert.*
import org.junit.Test

class PointerRolloverTest {
    private val tracker = PointerRollover()
    private val output = mutableListOf<String>()
    private var current = ""

    private fun down(id: Int, key: String, exclusive: Boolean = false) {
        tracker.down(id, exclusive, { output.add(current) }, { current = key })
    }

    private fun up(id: Int) = tracker.up(id) { output.add(current) }

    @Test fun overlappingTouchesKeepDownOrderWhenFirstFingerLiftsFirst() {
        down(7, "2"); down(19, "3"); up(7); up(19)
        assertEquals(listOf("2", "3"), output)
    }

    @Test fun overlappingTouchesKeepDownOrderWhenSecondFingerLiftsFirst() {
        down(7, "2"); down(19, "3"); up(19); up(7)
        assertEquals(listOf("2", "3"), output)
    }

    @Test fun repeatedSameKeyRemainsTwoStrokes() {
        down(0, "7"); down(1, "7"); up(1); up(0)
        assertEquals(listOf("7", "7"), output)
    }

    @Test fun threeOverlappingTouchesDoNotDuplicateOldUps() {
        down(4, "2"); down(8, "3"); down(12, "4")
        up(8); up(4); up(12)
        assertEquals(listOf("2", "3", "4"), output)
    }

    @Test fun cancellationDiscardsOnlyUnfinishedStroke() {
        down(0, "2"); down(1, "3")
        tracker.cancel { current = "" }
        up(1); up(0)
        assertEquals(listOf("2"), output)
        assertNull(tracker.activeId)
    }

    @Test fun heldVoiceSpaceKeepsOwnershipUntilItsOwnRelease() {
        down(4, "voice-stop")
        down(9, "letter", exclusive = true)
        up(9)
        assertTrue(output.isEmpty())
        assertEquals(4, tracker.activeId)
        up(4)
        assertEquals(listOf("voice-stop"), output)
    }

    @Test fun shortSpaceRolloverProducesOneSpaceBeforeNextLetter() {
        down(0, " "); down(1, "2"); up(0); up(1)
        assertEquals(listOf(" ", "2"), output)
    }

    @Test fun canceledVoiceDoesNotReleaseLater() {
        down(0, "voice-stop")
        tracker.cancel { output.add("voice-cancel") }
        up(0)
        assertEquals(listOf("voice-cancel"), output)
    }

    @Test fun pointerIdMayBeReusedAfterRelease() {
        down(0, "2"); up(0); down(0, "3"); up(0)
        assertEquals(listOf("2", "3"), output)
    }
}

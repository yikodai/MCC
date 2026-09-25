package mccandroid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McVersionTest {

    @Test
    fun `maps modern versions to protocol numbers`() {
        assertEquals(766, McVersion.versionToProtocol("1.20.6"))
        assertEquals(769, McVersion.versionToProtocol("1.21.4"))
        assertEquals(770, McVersion.versionToProtocol("1.21.5"))
        assertEquals(772, McVersion.versionToProtocol("1.21.8"))
        assertEquals(774, McVersion.versionToProtocol("1.21.11"))
        assertEquals(776, McVersion.versionToProtocol("26.2"))
    }

    @Test
    fun `accepts raw protocol numbers`() {
        assertEquals(776, McVersion.versionToProtocol("776"))
    }

    @Test
    fun `returns zero for unknown versions`() {
        assertEquals(0, McVersion.versionToProtocol("1.8.9"))
        assertEquals(0, McVersion.versionToProtocol("not-a-version"))
    }

    @Test
    fun `supported range matches documented protocol range`() {
        assertTrue(McVersion.isSupported(766))
        assertTrue(McVersion.isSupported(776))
        assertFalse(McVersion.isSupported(765))
        assertFalse(McVersion.isSupported(777))
    }
}
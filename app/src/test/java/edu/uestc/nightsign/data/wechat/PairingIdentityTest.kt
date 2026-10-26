package edu.uestc.nightsign.data.wechat

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PairingIdentityTest {
    private val seed = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
    @Test fun serverIdentitySurvivesTunnelAddressChanges() {
        val first = ServerPairingOffer("https://one.example.org", "c".repeat(43), "s".repeat(43))
        val second = first.copy(endpoint = "https://two.example.org", code = "d".repeat(43))
        assertEquals(PairingIdentity.installationKey(seed, first), PairingIdentity.installationKey(seed, second))
    }
    @Test fun installationsAndServersHaveDistinctKeys() {
        val offer = ServerPairingOffer("https://one.example.org", "c".repeat(43), "s".repeat(43))
        val otherSeed = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 8 })
        assertNotEquals(PairingIdentity.installationKey(seed, offer), PairingIdentity.installationKey(otherSeed, offer))
        assertNotEquals(PairingIdentity.installationKey(seed, offer), PairingIdentity.installationKey(seed, offer.copy(serverId = "t".repeat(43))))
    }
    @Test fun migrationProofMatchesServerAndIsBoundToTheNewCode() {
        val proof = PairingIdentity.previousProof("t".repeat(43), "c".repeat(43), "k".repeat(43))
        assertEquals("mBaGOTEQDoUa0xTzmmNlIwA_0d-meqLKva6Y208DL-U", proof)
        assertNotEquals(proof, PairingIdentity.previousProof("t".repeat(43), "d".repeat(43), "k".repeat(43)))
    }
}

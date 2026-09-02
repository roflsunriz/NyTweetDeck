package dev.nytweetdeck.android.xapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XApiMetadataStoreTest {
    @Test
    fun atomicallyAppliesACompleteSnapshotAndRetainsItAfterRefreshFailure() {
        val operation = XApiProfile.GraphQlOperation(
            "old-id", "HomeTimeline", XApiProfile.OperationType.QUERY, listOf("feature_a"), emptyList(),
        )
        val bundled = XApiProfile(
            graphqlBaseUrl = "https://x.com/i/api/graphql",
            featureKeys = listOf("feature_a"),
            featureDefaults = mapOf("feature_a" to false),
            operations = mapOf("home" to operation),
        )
        val resolved = XWebMetadataResolver.ResolvedMetadata(
            sourceVersion = "main.verified.js",
            operationsByName = mapOf(
                "HomeTimeline" to XWebMetadataResolver.ResolvedOperation(
                    "new-id", "HomeTimeline", XApiProfile.OperationType.QUERY,
                    listOf("feature_a"), emptyList(),
                ),
            ),
            allFeatureKeys = listOf("feature_a"),
            featureDefaults = mapOf("feature_a" to true),
        )
        var fail = false
        var loggedFailure: Throwable? = null
        val store = XApiMetadataStore(
            bundledProfile = bundled,
            resolver = { if (fail) error("offline") else resolved },
            warningLogger = { loggedFailure = it },
        )

        val success = store.refreshMetadata()
        assertTrue(success.succeeded)
        assertEquals("main.verified.js", success.sourceVersion)
        assertEquals("new-id", store.currentProfile().requireOperation("home").operationId)
        assertEquals(true, store.currentProfile().featureDefaults["feature_a"])

        fail = true
        assertFalse(store.refreshMetadata().succeeded)
        assertEquals("offline", loggedFailure?.message)
        assertEquals("new-id", store.currentProfile().requireOperation("home").operationId)
    }
}

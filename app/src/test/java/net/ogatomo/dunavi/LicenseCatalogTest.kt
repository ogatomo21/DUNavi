package net.ogatomo.dunavi

import net.ogatomo.dunavi.ui.licenseAsset
import net.ogatomo.dunavi.ui.licenseEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseCatalogTest {
    @Test fun everyDisplayedLicenseHasAnOfflineTextAndUniqueId() {
        assertTrue(AppLicenses.all.isNotEmpty())
        assertEquals(licenseEntries.size, licenseEntries.distinctBy { it.artifactId }.size)
        licenseEntries.forEach { license ->
            assertNotNull("Missing offline text for ${license.artifactId}", licenseAsset(license.licenseName))
        }
    }

    @Test fun sourceCopiedFreeProxyAndAppLicenseAreIncluded() {
        assertTrue(licenseEntries.any { it.artifactId == "net.ogatomo.dunavi" && it.licenseName == "MIT License" })
        assertTrue(licenseEntries.any { it.artifactId.startsWith("free-proxy:f13432fe") && it.licenseName == "Apache License 2.0" })
    }
}

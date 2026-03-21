package com.example.voicenoteapp.jobtread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobTreadOrganizationSelectionTest {
    @Test
    fun singleOrganizationIsAutoSelectedAndMarkedForPersistence() {
        val result = resolveOrganizationSelection(
            organizations = listOf(
                JobTreadOrganization(id = "org-1", name = "Acme Homes")
            ),
            savedOrganizationId = "",
            savedOrganizationName = ""
        )

        assertTrue(result is JobTreadOrganizationSelectionResolution.Selected)
        val selection = (result as JobTreadOrganizationSelectionResolution.Selected).selection
        assertEquals("org-1", selection.activeOrganization.id)
        assertTrue(selection.wasAutoSelected)
        assertTrue(selection.shouldPersistSelection)
    }

    @Test
    fun multiOrganizationUsesSavedDefaultWhenItStillMatches() {
        val result = resolveOrganizationSelection(
            organizations = listOf(
                JobTreadOrganization(id = "org-1", name = "Acme Homes"),
                JobTreadOrganization(id = "org-2", name = "BuildCo")
            ),
            savedOrganizationId = "org-2",
            savedOrganizationName = "BuildCo"
        )

        assertTrue(result is JobTreadOrganizationSelectionResolution.Selected)
        val selection = (result as JobTreadOrganizationSelectionResolution.Selected).selection
        assertEquals("org-2", selection.activeOrganization.id)
        assertFalse(selection.wasAutoSelected)
        assertFalse(selection.shouldPersistSelection)
    }

    @Test
    fun multiOrganizationRequiresSelectionWhenNoSavedDefaultMatches() {
        val result = resolveOrganizationSelection(
            organizations = listOf(
                JobTreadOrganization(id = "org-1", name = "Acme Homes"),
                JobTreadOrganization(id = "org-2", name = "BuildCo")
            ),
            savedOrganizationId = "",
            savedOrganizationName = ""
        )

        assertTrue(result is JobTreadOrganizationSelectionResolution.SelectionRequired)
        val selectionRequired = result as JobTreadOrganizationSelectionResolution.SelectionRequired
        assertEquals(2, selectionRequired.organizations.size)
        assertTrue(selectionRequired.message.contains("multiple organizations"))
    }
}

package com.ocrapp.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownTextInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun displaysDocumentCellsAndRefreshesWhenTheSourceChanges() {
        val markdown = mutableStateOf("# Invoice\n\n| Item | Qty |\n| --- | --- |\n| Bolt \\| cap | 4 |")
        compose.setContent { MaterialTheme { MarkdownText(markdown.value) } }

        waitForText("Invoice")
        compose.onNodeWithText("Bolt | cap").assertIsDisplayed()
        compose.onNodeWithText("4").assertIsDisplayed()

        compose.runOnIdle { markdown.value = "# Corrected invoice" }

        waitForText("Corrected invoice")
        compose.onNodeWithText("Invoice").assertDoesNotExist()
        compose.onNodeWithText("Bolt | cap").assertDoesNotExist()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}

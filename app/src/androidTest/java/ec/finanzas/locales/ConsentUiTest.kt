package ec.finanzas.locales

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import ec.finanzas.designsystem.FinanzasTheme
import ec.finanzas.onboarding.ConsentForm
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConsentUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun explicitCheckboxIsRequiredBeforeConsent() {
        val consented = AtomicBoolean(false)
        compose.setContent { FinanzasTheme {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                ConsentForm(saved = false, onConsent = { consented.set(true) }, onExplore = {})
            }
        } }
        compose.onNodeWithText("Aceptar y elegir fuentes").performScrollTo().assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Aceptar y elegir fuentes").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertTrue(consented.get())
    }
}

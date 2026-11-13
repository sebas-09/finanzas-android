package ec.finanzas.locales

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun shellShowsCaptureOffAndNavigatesAllSections() {
        compose.onNodeWithText("Explorar la app").performScrollTo().performClick()
        for (title in listOf("Conexión de notificaciones", "Configuración inicial", "Movimientos",
            "Por revisar", "Cuentas y tarjetas", "Categorías y presupuestos", "Ajustes")) {
            compose.onNodeWithText(title).performScrollTo().performClick()
            compose.onNodeWithText(title)
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Volver").performClick()
        }
    }
}


package ec.finanzas.locales

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.finanzas.capture.CaptureManager
import javax.inject.Inject

enum class StorageStatus { CHECKING, READY, UNAVAILABLE }

@HiltViewModel
class BootstrapViewModel @Inject constructor(val capture: CaptureManager) : ViewModel() {
    val state = capture.ui
}

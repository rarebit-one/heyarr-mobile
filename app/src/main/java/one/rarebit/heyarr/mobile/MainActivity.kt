package one.rarebit.heyarr.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import one.rarebit.heyarr.mobile.library.LibraryScreen
import one.rarebit.heyarr.mobile.login.LoginScreen
import one.rarebit.heyarr.mobile.login.LoginUiState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: AppViewModel = viewModel()
                val loginState by vm.loginState.collectAsStateWithLifecycle()
                val libraryState by vm.libraryState.collectAsStateWithLifecycle()
                Scaffold { padding ->
                    when (loginState) {
                        is LoginUiState.Approved ->
                            LibraryScreen(
                                state = libraryState,
                                modifier = Modifier.fillMaxSize().padding(padding),
                            )
                        else ->
                            LoginScreen(
                                state = loginState,
                                onSignIn = vm::signIn,
                                modifier = Modifier.padding(padding),
                            )
                    }
                }
            }
        }
    }
}

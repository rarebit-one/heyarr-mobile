package one.rarebit.heyarr.mobile.library

/** The whole-library read the app ViewModel keeps for the session (the shell's screens read it through `AppSession` instead). */
sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Loaded(val works: List<Work>) : LibraryUiState
    data class Error(val message: String) : LibraryUiState
}

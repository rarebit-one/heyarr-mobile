package one.rarebit.heyarr.mobile.net

/**
 * The live `Authorization` header value, for callers that are not our own transport:
 * the image loader and the media data source, which fetch through the shared OkHttp
 * client directly. The provider is swapped by the ViewModel as the credential changes
 * (QR session → Device cert → signed out); a null means "send nothing".
 */
class AuthHeaderSource {
    @Volatile
    var provider: () -> String? = { null }

    fun current(): String? = provider()
}

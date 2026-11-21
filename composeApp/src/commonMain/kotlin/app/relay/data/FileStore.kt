package app.relay.data

/** A file the person chose in the browser but has not uploaded yet. [handle] is platform-specific. */
class PickedFile(val name: String, val sizeBytes: Long, val mimeType: String, val handle: Any)

/** Document upload port. The browser implementation uses the file picker and Firebase Storage. */
interface FileStore {
    /** Opens the file picker. Returns an empty list when the person cancels. */
    suspend fun pick(): List<PickedFile>
    /** Uploads to [path] and returns a download URL that works for anyone holding the link (e.g. a replacement). */
    suspend fun upload(path: String, file: PickedFile): String
}

object DocLimits {
    const val MAX_FILES = 10
    const val MAX_BYTES = 10L * 1024 * 1024
    const val ACCEPT = ".pdf,.doc,.docx,.txt,.md,.rtf,.csv,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.jpeg,.webp"
}

fun readableSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** "Brief v2 (final).pdf" → "Brief_v2_final_.pdf" — safe for a storage path, keeps the extension. */
fun safeFileName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').takeLast(80).ifEmpty { "document" }

/** The note stored on every uploaded brief document, e.g. "Brief document · 1.2 MB". */
fun briefDocumentNote(file: PickedFile) = "Brief document · ${readableSize(file.sizeBytes)}"

/**
 * Uploads one brief document to Storage and records it as a "reference" file on the project, so it
 * shows in the Files tab and is carried into every handoff for the next freelancer.
 */
suspend fun RelayRepository.addBriefDocument(user: AuthUser, projectId: String, store: FileStore, file: PickedFile) {
    val url = store.upload("projects/$projectId/brief/${db.newId()}-${safeFileName(file.name)}", file)
    addAsset(user, projectId, file.name, url, "reference", briefDocumentNote(file))
}

/** Returns a reason to refuse [file], or null when it can be attached next to [current]. */
fun rejectReason(file: PickedFile, current: List<PickedFile>): String? = when {
    file.sizeBytes > DocLimits.MAX_BYTES -> "${file.name} is ${readableSize(file.sizeBytes)}. Files can be up to 10 MB."
    file.sizeBytes == 0L -> "${file.name} is empty."
    current.size >= DocLimits.MAX_FILES -> "You can attach up to ${DocLimits.MAX_FILES} documents."
    else -> null
}

/** True for an uploaded file (Firebase Storage, its emulator, or a Demo-mode blob: URL), as opposed to a pasted Figma/Drive link. */
fun isUploadedFile(url: String): Boolean = url.startsWith("blob:") || (url.contains("/v0/b/") && url.contains("/o/"))

/** Subtitle for a file row: uploaded documents show their note ("Brief document · 1.2 MB"), links show the URL. */
fun fileSubtitle(url: String, note: String): String = if (isUploadedFile(url)) note.ifBlank { "Uploaded document" } else url

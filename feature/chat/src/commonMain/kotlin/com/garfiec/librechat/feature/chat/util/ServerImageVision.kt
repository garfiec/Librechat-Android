package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.FileObject

/** True for any file the client treats as an image (mirrors the server's `image/` vision routing). */
internal fun isImageType(type: String): Boolean = type.startsWith("image/")

/**
 * Filenames of picked server images the model won't be able to read as an image.
 *
 * The server's vision encoder (`api/server/services/Files/images/encode.js`) skips any file whose
 * stored `height` is falsy (null/0): it never becomes an `image_url`, so the model never sees the
 * pixels. The server re-resolves attachments by `file_id` from the same DB the file list reads, so
 * this height is exactly the value the encoder will check — the result here is exact, not a guess.
 *
 * The file is still attached (the server keeps a heightless image as a plain file record, and an
 * agent may route it to a tool); this only drives a heads-up so a picked image doesn't silently do
 * nothing on a vision model (issue #252). Non-image files never appear here — they attach as
 * document context with no height requirement, and freshly device-uploaded images always carry
 * server-computed dimensions, which is why that path never trips this.
 */
internal fun visionUnreadableImageNames(files: List<FileObject>): List<String> =
    files.filter { isImageType(it.type) && (it.height == null || it.height == 0) }
        .map { it.filename }

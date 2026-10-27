package com.rightpath.dto;

/**
 * The bytes of one stored capture, together with what they should be served as.
 *
 * @param bytes       the image content pulled back out of storage
 * @param contentType the media type recorded at upload time
 * @param fileName    a download-friendly name for the image
 */
public record ProctoringCaptureImage(byte[] bytes, String contentType, String fileName) {
}

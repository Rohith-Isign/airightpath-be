package com.rightpath.service;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

	String uploadFile(String containerName, String fileName, MultipartFile file);

	byte[] downloadFile(String containerName, String fileName);

	String downloadFileAsText(String containerName, String fileName);

	boolean fileExists(String containerName, String fileName);

	/** Names (without the prefix) of every object stored under a prefix. */
	java.util.List<String> listFileNames(String prefix);

	void uploadStringContent(String prefix, String fileName, String content);

	/**
	 * A time-limited, directly-fetchable URL for something already stored.
	 *
	 * <p>Takes the reference {@link #uploadFile} returned, so callers never have
	 * to know how a stored location is spelled. That spelling is why this exists:
	 * uploads return an {@code s3://} URI, a browser cannot open that scheme, and
	 * a reviewer clicking an interview recording got a blank tab.</p>
	 *
	 * <p>A signed URL rather than bytes through the application: a screen
	 * recording of a full interview runs to hundreds of megabytes, and a video
	 * player needs range requests to seek at all.</p>
	 *
	 * @param storedReference the value returned by {@code uploadFile}
	 * @param ttl             how long the URL stays valid
	 */
	String presignedUrl(String storedReference, java.time.Duration ttl);

	/**
	 * The same link, but asking the browser to save the file rather than show it.
	 *
	 * <p>Separate from {@link #presignedUrl} because the disposition is signed
	 * into the URL — one link cannot both play inline and download, so the caller
	 * has to say which the person asked for.</p>
	 *
	 * @param downloadName the filename to save as
	 */
	String presignedDownloadUrl(String storedReference, String downloadName, java.time.Duration ttl);

	/**
	 * The size in bytes of a stored object, or empty if it is not there.
	 *
	 * <p>Signing a URL does not touch the object — a presigned link to a key
	 * that was never written looks exactly like one to a real recording, and the
	 * reviewer finds out only when the player refuses it with "format not
	 * supported". Checking first turns that into a straight answer about whether
	 * the recording was saved.</p>
	 *
	 * <p>A present-but-empty object is its own failure and the caller needs to
	 * tell it apart from a missing one, which is why this returns the size
	 * rather than a boolean.</p>
	 */
	java.util.Optional<Long> objectSize(String storedReference);

	/**
	 * Start a multipart upload: a large object sent as separate pieces.
	 *
	 * <p>Exists because a whole interview recording in one request is fragile in
	 * ways nothing here controls. A proxy in front of the server that caps
	 * request bodies refuses it by closing the connection, and the candidate's
	 * browser reports a dropped connection — recordings of a few megabytes
	 * saved while the same interview's full-length recording never did. Sent as
	 * pieces of 5 MB, each request is small enough to get through, and a piece
	 * that fails is sent again on its own instead of the whole file.</p>
	 *
	 * @return the upload id to quote on every later call
	 */
	String beginMultipartUpload(String containerName, String fileName, String contentType);

	/** Send one piece. Pieces other than the last must be at least 5 MB. */
	void uploadMultipartPart(String containerName, String fileName, String uploadId, int partNumber, byte[] data);

	/**
	 * Join the pieces into the finished object.
	 *
	 * @return the reference to store, in the same form {@link #uploadFile} returns
	 */
	String completeMultipartUpload(String containerName, String fileName, String uploadId);

	/** Throw away a started upload that will not be finished. */
	void abortMultipartUpload(String containerName, String fileName, String uploadId);
	 
}

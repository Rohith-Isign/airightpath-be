# Recording upload straight to S3

Large interview recordings (up to 2 GB) go from the browser to S3 on presigned URLs. The bytes never
pass through an API pod, so a few concurrent long uploads can no longer exhaust a pod's heap.

## Flow (candidate; the phone uses the same shape with `token` instead of auth)

1. `POST /api/interview/{id}/recording-upload?kind=camera|screen|mobile` returns `{uploadId, blobName}` (unchanged).
2. For each piece, `POST /api/interview/{id}/recording-upload/part-url?uploadId=&blobName=&part=N&size=BYTES`
   returns `{url, method: "PUT"}`. `size` is the exact byte length of the piece and is signed into the URL.
3. `PUT` the piece to `url`. Send the body only, with no extra headers other than the ones the browser adds.
   S3 refuses a body whose length differs from `size`. The URL is valid for 15 minutes.
4. `POST .../recording-upload/complete?kind=&uploadId=&blobName=` joins what S3 holds (unchanged). No ETags
   need to be sent back.

Phone: `POST /api/mobile/recording-upload/part-url?token=&uploadId=&blobName=&part=N&size=BYTES`
(`/recording-upload` and `/recording-upload/complete` are unchanged). `size` counts against the pairing's
upload allowance.

Rules: every piece except the last is at least 5 MB; at most 10,000 pieces; each piece at most
`recording.part-max-bytes` (default 256 MB). A 2 GB recording in 8 to 16 MB pieces is a few hundred pieces.

A piece that fails is requested again with a fresh URL. A request for a recording the server has already
finished answers 410 and the browser stops.

## Old endpoints

`.../recording-upload/part` (bytes through the API) still works for clients that have not moved, but a piece
larger than `recording.legacy-part-max-bytes` (default 32 MB) is now refused with 413 before it is read.

## One-time infrastructure: bucket CORS

The browser PUTs to the bucket's own origin, so the bucket must allow it:

```json
[
  {
    "AllowedOrigins": ["https://<frontend-origin>"],
    "AllowedMethods": ["PUT"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3000
  }
]
```

The IAM identity the API runs as needs `s3:PutObject` (parts are written under the object's key),
`s3:AbortMultipartUpload` and `s3:ListMultipartUploadParts` on the bucket. Consider a lifecycle rule that
aborts incomplete multipart uploads after a few days, so abandoned pieces do not cost storage.

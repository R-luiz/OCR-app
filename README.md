# OCR-app

Android OCR client that sends images to a backend powered by [baidu/Unlimited-OCR](https://github.com/baidu/Unlimited-OCR), deployed on a Fly.io machine.

## Backend

Deploy Unlimited-OCR on Fly.io and expose an HTTPS endpoint (example: `https://unlimited-ocr.fly.dev`).

## Android integration contract

The Android app should:

1. Pick or capture an image.
2. `POST` the image as `multipart/form-data` to the Fly.io OCR endpoint.
3. Parse and display recognized text from the JSON response.

### Example request

```http
POST /ocr HTTP/1.1
Host: unlimited-ocr.fly.dev
Content-Type: multipart/form-data
```

### Example minimal Kotlin call (OkHttp)

```kotlin
val file = File(imagePath)
val requestBody = MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .addFormDataPart("file", file.name, file.asRequestBody("image/*".toMediaType()))
    .build()

val request = Request.Builder()
    .url("https://unlimited-ocr.fly.dev/ocr")
    .post(requestBody)
    .build()

OkHttpClient().newCall(request).enqueue(object : Callback {
    override fun onFailure(call: Call, e: IOException) {}
    override fun onResponse(call: Call, response: Response) {
        val body = response.body?.string().orEmpty()
        // Parse OCR text from backend JSON response
    }
})
```

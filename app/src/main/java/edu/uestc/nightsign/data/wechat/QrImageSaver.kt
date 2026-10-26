package edu.uestc.nightsign.data.wechat

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream

object QrImageSaver {
    fun png(encoded: String): ByteArray {
        require(encoded.length <= 3_000_000)
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048) { "二维码图片无效" }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("二维码图片无效")
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    fun gallery(context: Context, encoded: String): Uri {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val bytes = png(encoded)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "wechat-qr-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/UESTCNightSign")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建图片")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法保存图片")
            val published = resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            check(published > 0) { "无法保存图片" }
            return uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}

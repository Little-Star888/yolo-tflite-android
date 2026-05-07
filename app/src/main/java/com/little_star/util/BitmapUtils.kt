package com.little_star.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface

/**
 * 位图工具类
 * 提供常用的位图处理功能
 */
object BitmapUtils {

    /**
     * 从 JPEG 文件解码位图并自动校正 EXIF 方向
     *
     * BitmapFactory.decodeFile 不会自动应用 EXIF 旋转信息，
     * 此方法读取 EXIF TAG_ORIENTATION 并通过 Matrix 一次性完成旋转和镜像翻转。
     * 同时设置前置摄像头的水平翻转标记。
     *
     * @param filePath JPEG 文件路径
     * @return 校正后的位图
     */
    fun decodeJpegWithExifRotation(filePath: String): Bitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeFile(filePath, options)
            ?: throw IllegalArgumentException("Cannot decode image: $filePath")

        val exif = ExifInterface(filePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 ->
                matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.postRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 ->
                matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 ->
                matrix.postRotate(270f)
        }

        // 如果矩阵不是初始状态，说明需要应用变换
        if (!matrix.isIdentity) {
            val transformed = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (transformed != bitmap) {
                bitmap.recycle()
            }
            bitmap = transformed
        }

        return bitmap
    }
}

package com.skateboard.cameralib.util

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Camera.Size
import java.util.*
import android.view.Surface
import android.view.WindowManager


class CameraManager
{
    var camera: Camera? = null

    private var width = 1080

    private var height = 1920

    constructor()

    var previewSize: Size? = null

    var picSize: Size? = null

    constructor(width: Int, height: Int)
    {
        this.width = width
        this.height = height
    }

    fun setSize(width: Int, height: Int)
    {
        this.width = width
        this.height = height
    }

    fun open(cameraId: Int): Boolean
    {

        val tCamera = camera
        try
        {
            if (tCamera == null)
            {
                camera = Camera.open(cameraId)
                camera?.let {
                    val parameters = it.parameters
                    previewSize = getBestSize(parameters.supportedPreviewSizes, width, height)
                    picSize = getBestSize(parameters.supportedPictureSizes, width, height)
                    parameters.setPreviewSize(previewSize?.width ?: 0, previewSize?.height ?: 0)
                    parameters.setPictureSize(picSize?.width ?: 0, picSize?.height ?: 0)
                    parameters.pictureFormat = ImageFormat.JPEG
                    parameters.setRotation(90)
                    it.parameters = parameters
                }
            }
        } catch (e: RuntimeException)
        {
            e.printStackTrace()
            return false
        }

        return true

    }

    fun takePicture(shutterCallback: Camera.ShutterCallback?, rawcallback: Camera.PictureCallback?, callback: Camera.PictureCallback?)
    {
        camera?.takePicture(shutterCallback, rawcallback, callback)
    }

    fun setBestDisplayOrientation(context: Context, cameraId: Int)
    {
        camera?.let {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(cameraId, info)
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val rotation = windowManager.defaultDisplay.rotation

            var degrees = 0
            when (rotation)
            {
                Surface.ROTATION_0 -> degrees = 0
                Surface.ROTATION_90 -> degrees = 90
                Surface.ROTATION_180 -> degrees = 180
                Surface.ROTATION_270 -> degrees = 270
            }
            var result: Int
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
            {
                result = (info.orientation + degrees) % 360
                result = (360 - result) % 360  // compensate the mirror
            } else
            {  // back-facing
                result = (info.orientation - degrees + 360) % 360
            }
            it.setDisplayOrientation(result)
        }


    }

    private fun getBestPictureSize(supportSizes: List<Size>, width: Int, height: Int, previewSize: Size): Size
    {
        for (size in supportSizes)
        {
            if (size.width == previewSize.width && size.height == previewSize.height)
            {
                return size
            }
        }

        return getBestSize(supportSizes, width, height)

    }


    private fun getBestSize(supportSizes: List<Size>, width: Int, height: Int): Size
    {
        Collections.sort(supportSizes, sizeComparator)
        val flipWidth = if (width < height) width else height
        val flipHeight = if (width < height) height else width
        val rate = flipWidth.toFloat() / flipHeight
        for (size in supportSizes)
        {
            val supportFlipWidth = if (size.width < size.height) size.width else size.height
            val supportFlipHeight = if (size.width < size.height) size.height else size.width
            if (supportFlipWidth == flipWidth && supportFlipHeight == flipHeight)
            {
                return size
            }

        }

        for (size in supportSizes)
        {

            if (equalRate(size, rate))
            {
                return size
            }
        }

        return supportSizes[0]

    }


    private fun equalRate(s: Size, rate: Float): Boolean
    {
        val flipWidth = if (s.width < s.height) s.width else s.height
        val flipHeight = if (s.width < s.height) s.height else s.width
        val r = flipWidth.toFloat() / flipHeight
        return Math.abs(r - rate) <= 0.05
    }


    private val sizeComparator = Comparator<Size> { lhs, rhs ->
        when
        {
            lhs.height * lhs.width == rhs.height * rhs.width -> 0
            lhs.height * lhs.width > rhs.height * rhs.width -> -1
            else -> 1
        }
    }

    fun setPreviewTexture(surfaceTexture: SurfaceTexture)
    {
        camera?.setPreviewTexture(surfaceTexture) ?: println("not set camera")
    }


    fun startPreview()
    {
        camera?.startPreview() ?: println("not set camera")
    }

    fun stopPreview()
    {
        camera?.stopPreview() ?: println("not set camera")
    }


    fun release()
    {
        camera?.setPreviewTexture(null)
        camera?.setPreviewCallback(null)
        camera?.stopPreview()
        camera?.release()
        camera = null
    }

}
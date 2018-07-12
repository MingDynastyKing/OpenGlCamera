package com.skateboard.cameralib.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * This class wraps up the core components used for surface-input video encoding.
 *
 *
 * Once created, frames are fed to the input surface.  Remember to provide the presentation
 * time stamp, and always call drainEncoder() before swapBuffers() to ensure that the
 * producer side doesn't get backed up.
 *
 *
 * This class is not thread-safe, with one exception: it is valid to use the input surface
 * on one thread, and drain the output on a different thread.
 */
class VideoEncoderCore
/**
 * Configures encoder and muxer state, and prepares the input Surface.
 */
@Throws(IOException::class)
constructor(mediaMuxerWrapper: MediaMuxerWrapper, width: Int, height: Int, bitRate: Int)
{

    /**
     * Returns the encoder's input surface.
     */
    val inputSurface: Surface
    private var mMuxer: MediaMuxerWrapper
    private var mEncoder: MediaCodec
    private val mBufferInfo: MediaCodec.BufferInfo
    private var mTrackIndex: Int = 0
    private var mMuxerStarted: Boolean = false


    init
    {
        mBufferInfo = MediaCodec.BufferInfo()

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
        if (VERBOSE) Log.d(TAG, "format: $format")

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE)
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mEncoder.createInputSurface()
        mEncoder.start()

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        mMuxer = mediaMuxerWrapper

        mTrackIndex = -1
        mMuxerStarted = false
    }

    /**
     * Releases encoder resources.
     */
    fun release()
    {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects")

        mEncoder.stop()
        mEncoder.release()


        // TODO: stop() throws an exception if you haven't fed it any data.  Keep track
        //       of frames submitted, and don't call stop() if we haven't written anything.
        mMuxer.stop()
        mMuxer.release()

    }

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     *
     *
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     *
     *
     * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
     * not recording audio.
     */
    fun drainEncoder(endOfStream: Boolean)
    {
        val TIMEOUT_USEC = 10000
        if (VERBOSE) Log.d(TAG, "drainEncoder($endOfStream)")

        if (endOfStream)
        {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder")
            mEncoder.signalEndOfInputStream()
        }

        var encoderOutputBuffers = mEncoder.outputBuffers
        while (true)
        {
            val encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC.toLong())
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER)
            {
                // no output available yet
                if (!endOfStream)
                {
                    break      // out of while
                } else
                {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS")
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)
            {
                // not expected for an encoder
                encoderOutputBuffers = mEncoder.outputBuffers
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
            {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted)
                {
                    throw RuntimeException("format changed twice")
                }
                val newFormat = mEncoder.outputFormat
                Log.d(TAG, "encoder output format changed: $newFormat")

                // now that we have the Magic Goodies, start the muxer
                mTrackIndex = mMuxer.addTrack(newFormat)
                mMuxer.start()
                mMuxerStarted = true
            } else if (encoderStatus < 0)
            {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: $encoderStatus")
                // let's ignore it
            } else
            {
                val encodedData = encoderOutputBuffers[encoderStatus]
                        ?: throw RuntimeException("encoderOutputBuffer " + encoderStatus +
                                " was null")

                if (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
                {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG")
                    mBufferInfo.size = 0
                }

                if (mBufferInfo.size != 0)
                {
                    if (!mMuxerStarted)
                    {
                        throw RuntimeException("muxer hasn't started")
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset)
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size)
                    if(mMuxer.isStarting())
                    {
                        mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo)
                        if (VERBOSE)
                        {
                            Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer, ts=" +
                                    mBufferInfo.presentationTimeUs)
                        }
                    }
                }

                mEncoder.releaseOutputBuffer(encoderStatus, false)

                if (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                {
                    if (!endOfStream)
                    {
                        Log.w(TAG, "reached end of stream unexpectedly")
                    } else
                    {
                        if (VERBOSE) Log.d(TAG, "end of stream reached")
                    }
                    break      // out of while
                }
            }
        }
    }

    companion object
    {
        private val TAG = "VideoEncoderCore"
        private val VERBOSE = false

        // TODO: these ought to be configurable as well
        private val MIME_TYPE = "video/avc"    // H.264 Advanced Video Coding
        private val FRAME_RATE = 30               // 60fps
        private val IFRAME_INTERVAL = 5           // 5 seconds between I-frames
    }
}

package com.skateboard.cameralib.codec

import android.media.MediaCodec
import android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

class AudioEncoderCore(private val mediaMuxerWrapper: MediaMuxerWrapper)
{
    private lateinit var audioCodec: MediaCodec

    private lateinit var bufferInfo: MediaCodec.BufferInfo

    private var trackIndex = -1

    private var isEncoding = false


    fun prepare(bitrate: Int, sampleRate: Int)
    {
        prepareAudioCodec(bitrate, sampleRate)
    }

    private fun prepareAudioCodec(bitrate: Int, sampleRate: Int)
    {
        bufferInfo = MediaCodec.BufferInfo()
        val mediaFormat = MediaFormat()
        mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        mediaFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC)
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
        mediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }



    fun start()
    {
        if (!isEncoding)
        {
            isEncoding = true
            audioCodec.start()
        }
    }

    fun drainEncoder(data: ByteArray)
    {

        val inIndex = audioCodec.dequeueInputBuffer(0)
        if (inIndex > 0)
        {
            val inBuffer = getInBuffer(inIndex)
            inBuffer.clear()
            inBuffer.put(data)
            if (!isEncoding)
            {
                audioCodec.queueInputBuffer(inIndex, 0, 0, System.nanoTime() / 1000, BUFFER_FLAG_END_OF_STREAM)

            } else
            {
                audioCodec.queueInputBuffer(inIndex, 0, data.size, System.nanoTime() / 1000, 0)
            }

        }

        do
        {
            val outIndex = audioCodec.dequeueOutputBuffer(bufferInfo, 0)
            when
            {
                outIndex > 0 ->
                {

                    if (bufferInfo.size != 0)
                    {
                        val outBuffer = getOutBuffer(outIndex)
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        if(mediaMuxerWrapper.isStarting())
                        {
                            mediaMuxerWrapper.writeSampleData(trackIndex, outBuffer, bufferInfo)
                        }
                    }
                    audioCodec.releaseOutputBuffer(outIndex, false)
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                {
                    trackIndex = mediaMuxerWrapper.addTrack(audioCodec.outputFormat)
                    mediaMuxerWrapper.start()
                }

            }
        } while (outIndex > 0)

        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
        {

            isEncoding = false
        }

    }

    fun release()
    {
        audioCodec.stop()
        audioCodec.release()
        mediaMuxerWrapper.stop()
        mediaMuxerWrapper.release()
    }


    private fun getInBuffer(index: Int): ByteBuffer
    {
        return if (Build.VERSION.SDK_INT >= 21)
        {
            audioCodec.getInputBuffer(index)
        } else
        {
            audioCodec.inputBuffers[index]
        }
    }

    private fun getOutBuffer(index: Int): ByteBuffer
    {
        return if (Build.VERSION.SDK_INT >= 21)
        {
            audioCodec.getOutputBuffer(index)
        } else
        {
            audioCodec.outputBuffers[index]
        }
    }

}
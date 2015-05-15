////////////////////////////////////////////////////////////////////////////////////////////////////
// AudioEncoder.java - Support encoding raw audio data into specified encoding type and           //
//                     streaming the encoded data into audible phsical files                      //                                                                                 //
// Ver 1.0                                                                                        //
// Language:    Java                                                                              //
// platform:    Windows 7, Android Studio 1.0.1                                                   //
// Application: IST 690 with Professor Carlos Caicedo, 2015 - Audio Capture Application           //
// Author:      Wenting Wang, wwang34@syr.edu                                                     //
// Source:      davidbrodsky, from GitHub                                                         //
//              https://github.com/OnlyInAmerica/HWEncoderExperiments/blob/audioonly/             //
//              HWEncoderExperiments/src/main/java/net/openwatch/hwencoderexperiments/            //
//              AudioEncoder.java                                                                 //
//              which in turn adapted from Andrew McFadden's MediaCodec examples                  //
//              http://bigflake.com/mediacodec/CameraToMpegTest.java.txt                          //
////////////////////////////////////////////////////////////////////////////////////////////////////
/*
* Class Operations:
* -------------------
* This class creates a MediaCodec instance, with the encoding type specified as "audio/mp4a-latm".
* The MediaCodec encodes raw code from the audio recording into this format. It also uses a
* MediaMuxer instance to stream the encoded data into audible files with the output format
* specified in the MediaMuxer's creation.
*
* Required Files:
* ---------------
*
*/

package com.example.testaudiocapturewiththreshold;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Class body
public class AudioEncoder {
    private static final String TAG = AudioEncoder.class.getSimpleName();
    private static final boolean VERBOSE = false;

    // AudioEncoder declaration and configuration
    private MediaCodec mAudioEncoder;
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";
    private MediaFormat audioFormat;
    private MediaCodec.BufferInfo mAudioBufferInfo;
    private ExecutorService encodingService = Executors.newSingleThreadExecutor(); // re-use encodingServiceI
    private TrackIndex mAudioTrackIndex = new TrackIndex();
    int encodingServiceQueueLength = 0;

    // MediaMuxer declaration and configuration
    private MediaMuxer mMuxer;
    private boolean mMuxerStarted;

    // Audio state
    private static long audioBytesReceived = 0;
    private static int numTracksAdded = 0;
    boolean eosReceived = false;
    boolean eosSentToAudioEncoder = false;
    boolean stopReceived = false;
    long audioStartTime = 0;
    int frameCount = 0;
    int totalInputAudioFrameCount = 0; // testing
    int totalOutputAudioFrameCount = 0;

    // Muxer state
    private static final int TOTAL_NUM_TRACKS = 1;

    Context c;

    //-------------< Used for debugging >------------------
    int timesderainEncoderWhileLoop = 0;

    //-------------< Constructor >--------------------------------------------------------------
    public AudioEncoder(Context c, MediaMuxer muxer) {
        this.c = c;
        this.mMuxer = muxer;
        prepare();
    }

    //-------------< Set private MediaMuxer >---------------------------------------------------
    public void setMediaMuxer(MediaMuxer muxer){
        this.mMuxer = muxer;
    }

    //-------------< Configure and prepare the AudioEncoder and set the Audio state >-----------
    private void prepare() {
        audioBytesReceived = 0;
        numTracksAdded = 0;
        frameCount = 0;
        eosReceived = false;
        eosSentToAudioEncoder = false;
        stopReceived = false;

        mAudioBufferInfo = new MediaCodec.BufferInfo();

        audioFormat = new MediaFormat();
        audioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        try{
            mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        } catch(IOException ioe){
            throw new RuntimeException("MediaCodec createEncoderByType failed", ioe);
        }
        mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();
    }

    // Stop the encodingService
    public void stop() {
        if (!encodingService.isShutdown()){
            encodingService.submit(new EncoderTask(this, EncoderTaskType.FINALIZE_ENCODER));
            //Log.w("AudioEncoder", "submitted an EncoderTask with type FINALIZE_ENCODER");
        }
    }

    // Called from encodingService
    public void _stop() {
        Log.w(TAG, "_stop()");
        stopReceived = true;
        eosReceived = true;
        logStatistics();
        //the following code copied from _offerAudioEncoder(), because they were not called inside it, _stop() was always called only after
        //the last time of execution of _offerAudioEncoder()
        closeEncoderAndMuxer(mAudioEncoder, mAudioBufferInfo, mAudioTrackIndex); // always called after video, so safe to close muxer
        eosSentToAudioEncoder = true;
        if (stopReceived) {
            Log.i(TAG, "Stopping Encoding Service");
            encodingService.shutdown();
        }
    }

    //-------------< Close the AudioEncoder and MediaMuxer >-------------------------------------------------
    public void closeEncoderAndMuxer(MediaCodec encoder, MediaCodec.BufferInfo bufferInfo, TrackIndex trackIndex) {
        Log.w(TAG, "CloseEncoderAndMuxer()");
        drainEncoder(encoder, bufferInfo, trackIndex, true);
        try {
            encoder.stop();
            encoder.release();
            encoder = null;
            closeMuxer();
            Log.w(TAG, "MediaMuxer has been closed");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //-------------< Close the AudioEncoder only >------------------------------------------------------------
    public void closeEncoder(MediaCodec encoder, MediaCodec.BufferInfo bufferInfo, TrackIndex trackIndex) {
        drainEncoder(encoder, bufferInfo, trackIndex, true);
        try {
            encoder.stop();
            encoder.release();
            encoder = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //-------------< Close the MediaMuxer only >------------------------------------------------------------
    public void closeMuxer() {
        Log.w(TAG, "Inside closeMuxer()");
        mMuxer.stop();
        mMuxer.release();
        mMuxer = null;
        mMuxerStarted = false;
    }

    // Called directly on the AudioEncoder instance
    public void offerAudioEncoder(byte[] input, long presentationTimeStampNs) {
        if (!encodingService.isShutdown()) {
            //long thisFrameTime = (presentationTimeNs == 0) ? System.nanoTime() : presentationTimeNs;
            encodingService.submit(new EncoderTask(this, input, presentationTimeStampNs));
            // Log.w(TAG, "submitted a new EncoderTask");
            encodingServiceQueueLength++;
        }
        else Log.w(TAG, "encodingService is already shut down");
    }

    // Called in encodeFrame method inside the EncoderTask
    private void _offerAudioEncoder(byte[] input, long presentationTimeNs) {
        if (audioBytesReceived == 0) {
            audioStartTime = presentationTimeNs;
        }
        totalInputAudioFrameCount++;
        audioBytesReceived += input.length;
        if (eosSentToAudioEncoder && stopReceived || input == null) {
            logStatistics();
            if (eosReceived) {
                Log.i(TAG, "EOS received in _offerAudioEncoder");
                closeEncoderAndMuxer(mAudioEncoder, mAudioBufferInfo, mAudioTrackIndex);
                eosSentToAudioEncoder = true;
                if (!stopReceived) {
                    // swap encoder
                    prepare();
                } else {
                    Log.i(TAG, "Shutting down Encoding Service");
                    encodingService.shutdown();
                }
            }
            return;
        }
        // transfer previously encoded data to muxer
        drainEncoder(mAudioEncoder, mAudioBufferInfo, mAudioTrackIndex, false);
        // send current frame data to encoder
        try {
            ByteBuffer[] inputBuffers = mAudioEncoder.getInputBuffers();
            int inputBufferIndex = mAudioEncoder.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                inputBuffer.put(input);
                long presentationTimeUs = (presentationTimeNs - audioStartTime) / 1000;
                if (eosReceived) {
                    Log.i(TAG, "EOS received in _offerEncoder");
                    mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    closeEncoderAndMuxer(mAudioEncoder, mAudioBufferInfo, mAudioTrackIndex);
                    eosSentToAudioEncoder = true;
                    if (stopReceived) {
                        Log.i(TAG, "Stopping Encoding Service");
                        encodingService.shutdown();
                    }
                } else {
                    mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, presentationTimeUs, 0);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "_offerAudioEncoder exception");
            t.printStackTrace();
        }
    }

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     * <p/>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     * <p/>
     * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
     * not recording audio.
     */
    private void drainEncoder(MediaCodec encoder, MediaCodec.BufferInfo bufferInfo, TrackIndex trackIndex, boolean endOfStream) {
        final int TIMEOUT_USEC = 100;
        Log.d(TAG, "drainEncoder (endOfStream:" + endOfStream + ")");
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
        while (true) {
            timesderainEncoderWhileLoop++;
            int encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {   //encoderStatus == -1;
                // no output available yet
                if (!endOfStream) {
                    Log.w(TAG, "Break by !endOfStream in drainEncoder");
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = encoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {   //encoderStatus == -2
                // should happen before receiving buffers, and should only happen once
                Log.w(TAG, "Inside if(encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)");
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed after muxer start");
                }
                MediaFormat newFormat = encoder.getOutputFormat();
                // now that we have the Magic Goodies, start the muxer
                trackIndex.index = mMuxer.addTrack(newFormat);
                numTracksAdded++;
                Log.d(TAG, "encoder output format changed: " + newFormat + ". Added track index: " + trackIndex.index);
                if (numTracksAdded == TOTAL_NUM_TRACKS) {
                    mMuxer.start();
                    mMuxerStarted = true;
                    Log.i(TAG, "All tracks added. Muxer started");
                }
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus]; //encoderStatus == 2, 0 - possibilities
                totalOutputAudioFrameCount++;
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    bufferInfo.size = 0;
                }
                if (bufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }
                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                    mMuxer.writeSampleData(trackIndex.index, encodedData, bufferInfo);
                }
                encoder.releaseOutputBuffer(encoderStatus, false);
                if(endOfStream){
                    Log.w(TAG, "end of stream reached");
                    break;
                }
            }
        }
        long endTime = System.nanoTime();
    }

    private void logStatistics() {
        Log.i(TAG + "-Stats", "audio frames input: " + totalInputAudioFrameCount + " output: " + totalOutputAudioFrameCount);
    }

    enum EncoderTaskType {
        ENCODE_FRAME, /*SHIFT_ENCODER,*/ FINALIZE_ENCODER;
    }

    // Can't pass an int by reference in Java...
    class TrackIndex {
        int index = 0;
    }

    //------------------------ < EncoderTask >-------------------------------------------------
    private class EncoderTask implements Runnable {
        private static final String TAG = "encoderTask";
        boolean is_initialized = false;
        long presentationTimeNs;
        private AudioEncoder encoder;
        private EncoderTaskType type;
        private byte[] audio_data;

        //-----------< Constructor 1 >-----------------------------------------
        public EncoderTask(AudioEncoder encoder, EncoderTaskType type) {
            setEncoder(encoder);
            this.type = type;
            switch (type) {
                /*
                case SHIFT_ENCODER:
                    setShiftEncoderParams();
                    break;
                */
                case FINALIZE_ENCODER:
                    setFinalizeEncoderParams();
                    break;
            }
        }
        //-----------< Constructor 2 >------------------------------------------
        public EncoderTask(AudioEncoder encoder, byte[] audio_data, long pts) {
            Log.w(TAG, "EncoderTask");
            setEncoder(encoder);
            setEncodeFrameParams(audio_data, pts);
        }

        //-----------< Constructor 3 >------------------------------------------
        public EncoderTask(AudioEncoder encoder) {
            setEncoder(encoder);
            setFinalizeEncoderParams();
        }
        private void setEncoder(AudioEncoder encoder) {
            Log.w(TAG, "setEncoder()");
            this.encoder = encoder;

        }

        private void setFinalizeEncoderParams() {
            Log.w(TAG, "setFinalizeEncoderParams()");
            is_initialized = true;
        }
        private void setEncodeFrameParams(byte[] audio_data, long pts) {
            this.audio_data = audio_data;
            this.presentationTimeNs = pts;

            is_initialized = true;
            this.type = EncoderTaskType.ENCODE_FRAME;
        }

        private void encodeFrame() {
            Log.w(TAG, "encodeFrame()");
            if (encoder != null && audio_data != null) {
                    encoder._offerAudioEncoder(audio_data, presentationTimeNs);
                    audio_data = null;
             }
        }

        private void finalizeEncoder() {
            Log.w(TAG, "finalizeEncoder()");
            encoder._stop();
        }

        @Override
        public void run() {
            Log.w(TAG, "EncoderTask run()");
            if (is_initialized) {
                switch (type) {
                    case ENCODE_FRAME:
                        encodeFrame();
                        break;
                    /*
                    case SHIFT_ENCODER:
                        shiftEncoder();
                        break;
                    */
                    case FINALIZE_ENCODER:
                        finalizeEncoder();
                        break;
                }
                // prevent multiple execution of same task
                is_initialized = false;
                encodingServiceQueueLength -= 1;
                //Log.i(TAG, "EncodingService Queue length: " + encodingServiceQueueLength);
            } else {
                Log.e(TAG, "run() called but EncoderTask not initialized");
            }
        }
    }
}
//////////////////////////////////////////////////////////////////////////////////////////////////////
// TestAudioCaptureWithThreshold.java - Support recording voice, detecting silence and              //
//                                      sending files to server                                     //                                                                                 //
// Ver 1.0                                                                                          //
// Language:    Java                                                                                //
// platform:    Windows 7, Android Studio 1.0.1                                                     //
// Application: IST 690 with Professor Carlos Caicedo, 2015 - Audio Capture Application             //
// Author:      Wenting Wang, wwang34@syr.edu                                                       //
// Source:      Gaucho, from Stack Overflow                                                         //
//              http://stackoverflow.com/questions/19145213/android-audio-capture-silence-detection //
//////////////////////////////////////////////////////////////////////////////////////////////////////
/*
* Class Operations:
* -------------------
* This class inflates a simple UI layout used to control voice recording. Voice is detected against a
* threshold according to its frequency. If the frequency of the voice is less than certain threshold,
* it's considered silence and won't be recorded if the length of the silence is longer than certain
* length (taking into account the length of natural pauses). If no voice has been detected for a
* considerable length of time, then the recording is automatically stopped and saved into a file in
* local storage. The file formated is defined when we create the MediaMuxer instance, in this case
* "MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4". If users agree to send the file to FTP server,
* the application will automatically connect to FTP server and upload the file. The sendtoFTPServer
* flag, threshold frequency, natural pause length, allowable pause length are all customerized
* options which users can change in the preference setting.
*
* Required Files:
* ---------------
*   - AudioEncoder.java, AudioCapturePreferenceActivity.java,
*     activity_test_audio_capture_with_threshold.xml
*
*/

package com.example.testaudiocapturewiththreshold;

import org.apache.commons.net.ftp.FTPClient;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.  Handler;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.TimeUnit;

// Class body
public class TestAudioCaptureWithThreshold extends ActionBarActivity{
    private static final String TAG = TestAudioCaptureWithThreshold.class.getSimpleName();

    // File storage related configuration
    private static final String AUDIO_RECORDER_FOLDER = "AudioRecorder";
    private static String AUDIO_RECORDER_TEMP_FILE;
    private static String STORED_FILE_NAME = " ";

    // Used for AudioRecord configuration
    public static final int FRAMES_PER_BUFFER = 24; // 1 sec @ 1024 samples/frame (aac)
    int bufferSize ;
    int frequency = 44100; //8000;
    int channelConfiguration = AudioFormat.CHANNEL_IN_MONO;
    int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;

    // UI control button and text display
    private Button mStartStopButton;
    private TextView mRecordingTextView;

    // Used to retain UI after rotation
    private static final String ButtonText = "ButtonText";
    private static final String RecordingTextViewText = "RecordingTextView";

    // Used to configure connected FTP server
    public static final String FTP_HOST = "192.168.0.11";
    private static final String FTP_USER = "gina";
    private static final String FTP_PASS = "1111";

    // Used for encoding and file streaming
    AudioEncoder mEncoder;
    MediaMuxer mMuxer;

    // Flag for continuation of recording
    boolean started = false;

    // Async task for recording
    RecordAudio recordTask;

    // Used in user preference settings
    public static boolean sendtoFTPServer = true;
    public static short threshold = 500;
    public static long naturalPauseLength = 5000000000L;
    public static long allowablePauseLength = 20000000000L;


    // Uer Preference Settings Change Event
    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        switch(item.getItemId())
        {
            case R.id.preferences:
            {
                Intent intent = new Intent(TestAudioCaptureWithThreshold.this, AudioCapturePreferenceActivity.class);
                startActivityForResult(intent, 0);
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.w(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_audio_capture_with_threshold);

        mRecordingTextView = (TextView)findViewById(R.id.recording_text_view);
        mStartStopButton = (Button)findViewById(R.id.start_stop_button);

        // Retain UI
        if(savedInstanceState !=null){
            mStartStopButton.setText(savedInstanceState.getString(ButtonText, "Start Recording"));
            mRecordingTextView.setText(savedInstanceState.getString(RecordingTextViewText, ""));
        }

        mStartStopButton.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v){  //The StartStopButton tooggle between these two states
                if(mStartStopButton.getText()=="Stop Recording"){
                    File file = new File(STORED_FILE_NAME);
                    if(file.exists()) {  // if the file actually exists
                        mRecordingTextView.setText("Recording stopped. File has been saved as " + STORED_FILE_NAME);
                    }
                    else{
                        mRecordingTextView.setText("Recording stopped.");
                    }
                    mStartStopButton.setText("Start Recording");
                    stopAquisition();
                }
                else{
                    mRecordingTextView.setText("Recording...");
                    mStartStopButton.setText("Stop Recording");
                    startAquisition();
                }
            }
        });
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState){
        super.onSaveInstanceState(savedInstanceState);
        Log.i(TAG, "onSaveInstanceState");
        savedInstanceState.putString(ButtonText, mStartStopButton.getText().toString());
        savedInstanceState.putString(RecordingTextViewText, mRecordingTextView.getText().toString());
    }

    @Override
    protected void onResume() {
        Log.w(TAG, "onResume");
        super.onResume();

    }

    @Override
    protected void onDestroy() {
        Log.w(TAG, "onDestroy");
        stopAquisition();
        super.onDestroy();
    }

    public class RecordAudio extends AsyncTask<Void, Double, Void> {

        public int samples_per_frame = 2048;    // codec-specific

        @Override
        protected Void doInBackground(Void... arg0) {
            Log.w(TAG, "doInBackground");
            // Get user preference settings
            SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
            sendtoFTPServer = SP.getBoolean("sendToFTPServer",false);
            threshold = Short.parseShort(SP.getString("thresholdFrequency", "500"));
            naturalPauseLength =  Integer.parseInt(SP.getString("naturalPauseLength", "5"))*1000000000L;
            allowablePauseLength = Integer.parseInt(SP.getString("allowablePauseLength", "120"))*1000000000L;

            try {

                File file = getTempFile();
                STORED_FILE_NAME = file.getAbsolutePath();
                int minBufferSize = AudioRecord.getMinBufferSize(frequency,
                        channelConfiguration, audioEncoding);

                bufferSize = samples_per_frame * FRAMES_PER_BUFFER;

                if (bufferSize < minBufferSize)
                    bufferSize = ((minBufferSize / samples_per_frame) + 1) * samples_per_frame * 2;

                short[] buffer = new short[bufferSize/2];

                AudioRecord audioRecord = new AudioRecord( MediaRecorder.AudioSource.MIC, frequency,
                        channelConfiguration, audioEncoding, bufferSize);

                // start receiving sound
                audioRecord.startRecording();

                boolean isCountingTime = false; // used to count how much continual silence time has passed
                long starttime = 0, stoptime = 0, elapsedtime = 0, totalelapsedtime = 0;

                while (started) {
                    byte[] this_buffer;
                    this_buffer = new byte[samples_per_frame];
                    int bufferReadResult = audioRecord.read(buffer, 0,samples_per_frame/2);

                    if(AudioRecord.ERROR_INVALID_OPERATION != bufferReadResult) {
                        //check signal
                        //put a threshold
                        int foundPeak = searchThreshold(buffer, threshold);
                        if (foundPeak > -1) {
                            //Log.w(TAG, "AudioRecord - Inside foundPeak>-1");
                            if (isCountingTime == true) {
                                if (elapsedtime > naturalPauseLength)
                                    totalelapsedtime += elapsedtime - naturalPauseLength;
                                elapsedtime = 0;
                            }
                            isCountingTime = false;
                            //if the output file stream doesn't exist, create it, else ignore this step
                            if (mEncoder == null) {
                                //Log.w(TAG, "creating AudioEncoder instance");
                                try {
                                    mMuxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                                } catch (IOException ioe) {
                                    throw new RuntimeException("MediaMuxer creation failed", ioe);
                                }
                                mEncoder = new AudioEncoder(getApplicationContext(), mMuxer);
                            }

                            //--------< the following time stamp code used for debugging >-----------------------------
                            Calendar c = Calendar.getInstance();
                            Log.w(TAG, "recorded sound at " + c.get(Calendar.HOUR_OF_DAY) + ":" + c.get(Calendar.MINUTE) + ":" + c.get(Calendar.SECOND));

                            //found signal
                            //record signal
                            if (mEncoder != null) {
                                this_buffer = ShortToByte(buffer, bufferReadResult);
                                mEncoder.offerAudioEncoder(this_buffer, System.nanoTime() - totalelapsedtime);
                            }
                        } else {
                            //count the time
                            //don't save signal
                            if (isCountingTime == false) {
                                starttime = System.nanoTime();
                            }
                            isCountingTime = true;
                            stoptime = System.nanoTime();
                            elapsedtime = stoptime - starttime;

                            Log.w(TAG, "no voice detected for " + TimeUnit.SECONDS.convert(elapsedtime, TimeUnit.NANOSECONDS) + " seconds");

                            //if the file output stream has been created and if the silent time is no more than 5 seconds, still record the blank
                            if (mEncoder != null && elapsedtime <= naturalPauseLength) {
                                this_buffer = ShortToByte(buffer, bufferReadResult);
                                mEncoder.offerAudioEncoder(this_buffer, System.nanoTime() - totalelapsedtime);
                                continue;
                            }
                            //if elapsed time is longer than 20 seconds
                            if (elapsedtime > allowablePauseLength) break;
                        }
                    }
                }
                if (audioRecord != null) {
                    audioRecord.stop();
                    Log.i("audioRecord", "stopped");
                }

                // Post to the UI thread
                mStartStopButton.post(new Runnable() {
                    public void run() {
                        if(mStartStopButton.getText()=="Stop Recording"){
                            File file = new File(STORED_FILE_NAME);
                            if(file.exists()){
                                mRecordingTextView.setText("Recording stopped. File has been saved as " + STORED_FILE_NAME);
                            }
                            else{
                                mRecordingTextView.setText("Recording stopped.");
                            }
                            mStartStopButton.setText("Start Recording");
                        }
                    }
                });

                // stop AudioEncoder instance
                if(mEncoder!=null){
                    mEncoder.stop();
                    mEncoder = null;
                }

                // if sendtoFTPServer flag is true, upload file to FTP server
                if(sendtoFTPServer) {
                    uploadFile(file);
                    mRecordingTextView.post(new Runnable(){
                        public void run(){
                            if(mStartStopButton.getText()=="Stop Recording"){
                                mRecordingTextView.append(" and has been sent to the FTP server.");
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                t.printStackTrace();
                Log.e("AudioRecord", "Recording Failed");
            }
            return null;

        } //end of doInBackground

        byte [] ShortToByte(short [] input, int elements) {
            int short_index, byte_index;
            int iterations = elements; //input.length;
            byte [] buffer = new byte[iterations * 2];
            short_index = byte_index = 0;
            for(/*NOP*/; short_index != iterations; /*NOP*/)
            {
                buffer[byte_index]     = (byte) (input[short_index] & 0x00FF);
                buffer[byte_index + 1] = (byte) ((input[short_index] & 0xFF00) >> 8);

                ++short_index; byte_index += 2;
            }
            return buffer;
        }


        // decide if the recorded samples surpasses the threshold
        int searchThreshold(short[]arr,short thr){
            int peakIndex;
            int arrLen=arr.length;
            for (peakIndex=0;peakIndex<arrLen;peakIndex++){
                if ((arr[peakIndex]>=thr) || (arr[peakIndex]<=-thr)){
                    // if it surpasses the threshold, returns the peak Hertz
                    return peakIndex;
                }
            }
            return -1; //not found
        }

        // Generate a file for storing the recorded voice
        private File getTempFile() {
            String filepath = Environment.getExternalStorageDirectory().getPath();
            File file = new File(filepath, AUDIO_RECORDER_FOLDER);
            if (!file.exists()) {
                file.mkdirs();
            }
            AUDIO_RECORDER_TEMP_FILE = new Date().getTime() + ".m4a";  // generate a file name
            File tempFile = new File(filepath, AUDIO_RECORDER_TEMP_FILE);
            if (tempFile.exists())
                tempFile.delete();
            file = new File(file.getAbsolutePath(), AUDIO_RECORDER_TEMP_FILE);
            return file;
        }

        // upload the file to a FTP server
        public void uploadFile(File fileName){
            FTPClient client = new FTPClient();
            try {
                //client.setConnectTimeout(1000000);
                Log.w(TAG, "Connecting to FTP Server");
                client.connect(FTP_HOST,21);
                client.login(FTP_USER, FTP_PASS);
                Log.w(TAG, "Logged on");
                client.setFileType(FTPClient.BINARY_FILE_TYPE);
                client.changeWorkingDirectory("/upload/");
                client.storeFile(fileName.getName(),new FileInputStream(fileName));
                Log.w(TAG, "File uploaded");

            } catch (IOException e) {
                e.printStackTrace();
            } finally{
                try {
                    Log.w(TAG, "Trying to disconnect");
                    client.disconnect();
                } catch (IOException e2) {
                    e2.printStackTrace();
                }
            }

        }
    } //End of RecordAudio (AsyncTask)

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_test_audio_capture_with_threshold,
                menu);
        return true;
    }

    public void resetAquisition() {
        Log.w(TAG, "resetAquisition");
        stopAquisition();
        startAquisition();
    }

    public void stopAquisition() {
        Log.w(TAG, "stopAquisition");
        if (started) {
            started = false;
            //recordTask.cancel(true);
        }
    }

    public void startAquisition(){
        Log.w(TAG, "startAquisition");
        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            public void run() {
                //elapsedTime=0;
                started = true;
                recordTask = new RecordAudio();
                recordTask.execute();
            }
        }, 10);
    }

}

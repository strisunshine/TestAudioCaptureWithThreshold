////////////////////////////////////////////////////////////////////////////////////////////////////
// AudioCapturePreferenceActivity.java - Support preference setting menu options                  //                                                                                           //
// Ver 1.0                                                                                        //
// Language:    Java                                                                              //
// platform:    Windows 7, Android Studio 1.0.1                                                   //
// Application: IST 690 with Professor Carlos Caicedo, 2015 - Audio Capture Application           //
// Author:      Wenting Wang, wwang34@syr.edu                                                     //
////////////////////////////////////////////////////////////////////////////////////////////////////
/*
* Class Operations:
* -------------------
* This class extends the PreferenceActivity class and thus use Google's Preference framework to
* automate the saving and effecting of user preference settings. This preference activity was
* started from the UI when user presses the Settings menu on the ActionBar. A xml resource will be
* used for the automatic layout within the framework. In this case, a checkbox, three item lists
* are used to enable user to configure their preference towards if they want to send the file to
* FTP server, what's the threshold frequency value for recording the voice, what's the maximum
* natural pause length they like and How long can the longest pause be before saving into a file.
*
* Required Files:
* ---------------
* preferences.xml
*
*/

package com.example.testaudiocapturewiththreshold;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceFragment;

// Class body
public class AudioCapturePreferenceActivity extends android.preference.PreferenceActivity {
    private static final String TAG = AudioCapturePreferenceActivity.class.getSimpleName();

    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction().replace(android.R.id.content, new AudioCapturePreferenceFragment()).commit();
    }

    public static class AudioCapturePreferenceFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener{

       @Override
       public void onCreate(Bundle savedInstanceState){
           super.onCreate(savedInstanceState);
           addPreferencesFromResource(R.xml.preferences);
       };

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key){
        }
    }
}

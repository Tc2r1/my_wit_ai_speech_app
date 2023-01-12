package com.tc2r1.azurevoice;

import android.Manifest;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.tc2r1.azurevoice.databinding.ActivityMainBinding;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;


public class MainActivity extends AppCompatActivity {
    private Button speakButton;
    private TextView speechTranscription;
    private TextToSpeech textToSpeech;

    private ActivityMainBinding binding;

    private OkHttpClient httpClient;
    private HttpUrl.Builder httpBuilder;
    private Request.Builder httpRequestBuilder;


    private AudioRecord recorder;
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, AUDIO_FORMAT) * 10;
    private static final AtomicBoolean recordingInProgress = new AtomicBoolean(false);
    private Thread recordingThread;

    /* Go to your Wit.ai app Management > Settings and obtain the Client Access Token */
    private static final String CLIENT_ACCESS_TOKEN = "EW4RAR2MA7WYSIHCR57E5OBN6UFGD55T";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        speakButton = binding.speakBtn;
        speechTranscription = binding.speechTranscriptTV;

        if(!checkPermissionsFromDevice()) {requestPermissions();}

        // Get a reference to the TextView and Button from the UI

        // Initialize TextToSpeech
        initializeTextToSpeech(this.getApplicationContext());

        // Initialize HTTP Client
        initializeHttpClient();

        // Wire up speakButton to an onClickListener
        speakButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d("speakButton", "clicked");
                if(!recordingInProgress.get()) {
                    startRecording();
                    speakButton.setText("Listening ...");
                    Log.d("speakButton", "Start listening ...");
                } else {
                    stopRecording();
                    speakButton.setText("Speak");
                    Log.d("speakButton", "Stop listening ...");
                }
            }
        });
    }

    // Processes the response from Speech API and responds to the user appropriately
    // See here for shape of the response: https://wit.ai/docs/http#get__message_link
    private void respondToUser(String response) {
        Log.v("respondToUser", response);
        String witIntentName = "";
        String witResponseText;
        String querySubject;
        Intent andIntent;
        JSONObject witDataObject = null;

        try {
            // Parse the intent name from the Wit.ai response
            witDataObject = new JSONObject(response);

            // Update the TextView with the voice transcription
            // Run it on the MainActivity's UI thread since it's the owner
            final String witUtterance = witDataObject.getString("text");
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    speechTranscription.setText(witUtterance);
                }
            });

            // Get most confident intent
            JSONObject intent = getMostConfident(witDataObject.getJSONArray("intents"));
            if(intent == null) {
                textToSpeech.speak("Sorry, I didn't get that. What is your name?", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                return;
            }
            witIntentName = intent.getString("name");
            Log.v("respondToUser", witIntentName);
        } catch(JSONException e) {
            e.printStackTrace();
        }

        // Handle intents
        switch(witIntentName) {
            case "greetings_intent":
                // Parse and get the most confident entity value for the name
                querySubject = getEntityValue(witDataObject, "wit$contact:contact");

                Log.v("respondToUser", querySubject);

                witResponseText = "Nice to meet you " + querySubject;
                textToSpeech.speak(witResponseText, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());

                break;
            case "search_intent":
                querySubject = getEntityValue(witDataObject, "wit$search:search");

                Log.v("respondToUser", "Searched For: " + querySubject);

                andIntent = new Intent(Intent.ACTION_WEB_SEARCH);
                andIntent.putExtra(SearchManager.QUERY, querySubject);

                witResponseText = "beginning Search For " + querySubject;
                textToSpeech.speak(witResponseText, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                startActivity(andIntent);

                break;
            case "wit$create_alarm":
                // https://developer.android.com/guide/components/intents-common#Clock
                querySubject = getEntityValue(witDataObject, "wit$datetime:datetime");

                DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ", Locale.ENGLISH);
                DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("dd-MM-yyy hh:mm:ss a", Locale.ENGLISH);

                LocalDateTime dateTime = LocalDateTime.parse(querySubject, inputFormatter);

                String formattedDate = outputFormatter.format(dateTime);
                System.out.println(formattedDate);

                andIntent = new Intent(AlarmClock.ACTION_SET_ALARM)
                                             .putExtra(AlarmClock.EXTRA_HOUR, dateTime.getHour())
                                             .putExtra(AlarmClock.EXTRA_MINUTES, dateTime.getMinute());

                witResponseText = "Setting Alarm for" + dateTime.toLocalTime().toString();
                textToSpeech.speak(witResponseText, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                startActivity(andIntent);

                break;
            case "wit$play":
                querySubject = getEntityValue(witDataObject, "wit$creative_work:creative_work");

                andIntent = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
                andIntent.putExtra(SearchManager.QUERY, querySubject);
                startActivity(andIntent);

                break;
            default:
                // If there is no matching intent, let the user know and ask them to try again
                textToSpeech.speak("What did you say is your name?", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                break;
        }
    }

    @NonNull
    private String getEntityValue(JSONObject witDataObject, String name){
        JSONObject entity;
        String querySubject = "";
        try {
            entity = getMostConfident((witDataObject.getJSONObject("entities")).getJSONArray(name));
            querySubject = (String) entity.get("value");

        } catch(JSONException e) {
            Log.d("Wit.Ai", e.getMessage());
        }
        return querySubject;
    }

    // Get the resolved intent or entity with the highest confidence from Wit Speech API
    // https://wit.ai/docs/recipes#which-confidence-threshold-should-you-use
    private JSONObject getMostConfident(JSONArray list) {
        JSONObject confidentObject = null;
        double maxConfidence = 0.0;
        for(int i = 0; i < list.length(); i++) {
            try {
                JSONObject object = list.getJSONObject(i);
                double currConfidence = object.getDouble("confidence");
                if(currConfidence > maxConfidence) {
                    maxConfidence = currConfidence;
                    confidentObject = object;
                }
            } catch(JSONException e) {
                e.printStackTrace();
            }
        }
        return confidentObject;
    }

    // Instantiate a new AudioRecord and start streaming the recording to the Wit Speech API
    private void startRecording() {
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }

        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, AUDIO_FORMAT, BUFFER_SIZE);
        recorder.startRecording();
        recordingInProgress.set(true);
        recordingThread = new Thread(new StreamRecordingRunnable(), "Stream Recording Thread");
        recordingThread.start();
    }

    // Release resources for the AudioRecord and Runnable when recording is stopped
    private void stopRecording() {
        if(recorder == null) {return;}
        recordingInProgress.set(false);
        recorder.stop();
        recorder.release();
        recorder = null;
        recordingThread = null;
    }

    // Define a Runnable to stream the recording data to the Speech API
    // https://wit.ai/docs/http#post__speech_link
    private class StreamRecordingRunnable implements Runnable {
        @Override
        public void run() {
            final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            RequestBody requestBody = new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.parse("audio/raw;encoding=signed-integer;bits=16;rate=8000;endian=little");
                }

                @Override
                public void writeTo(@NotNull BufferedSink bufferedSink) throws IOException {
                    while(recordingInProgress.get()) {
                        int result = recorder.read(buffer, BUFFER_SIZE);
                        if(result < 0) {
                            throw new RuntimeException("Reading of audio buffer failed: " + getBufferReadFailureReason(result));
                        }
                        bufferedSink.write(buffer);
                        buffer.clear();
                    }
                }
            };

            // Start streaming audio to Wit.ai Speech API
            Request request = httpRequestBuilder.post(requestBody).build();
            try(Response response = httpClient.newCall(request).execute()) {
                if(response.isSuccessful()) {
                    String responseData = response.body().string();
                    respondToUser(responseData);
                    Log.v("Streaming Response", responseData);
                }
            } catch(IOException e) {
                Log.e("Streaming Response", e.getMessage());
            }
        }

        private String getBufferReadFailureReason(int errorCode) {
            switch(errorCode) {
                case AudioRecord.ERROR_INVALID_OPERATION:
                    return "ERROR_INVALID_OPERATION";
                case AudioRecord.ERROR_BAD_VALUE:
                    return "ERROR_BAD_VALUE";
                case AudioRecord.ERROR_DEAD_OBJECT:
                    return "ERROR_DEAD_OBJECT";
                case AudioRecord.ERROR:
                    return "ERROR";
                default:
                    return "Unknown (" + errorCode + ")";
            }
        }
    }

    // Instantiate a Request.Builder that can be used for all the streaming requests
    // https://square.github.io/okhttp/recipes/#post-streaming-kt-java
    // https://wit.ai/docs/http#post__speech_link
    private void initializeHttpClient() {
        httpClient = new OkHttpClient();
        httpBuilder = HttpUrl.parse("https://api.wit.ai/speech").newBuilder();
        httpBuilder.addQueryParameter("v", "20200805");
        httpRequestBuilder = new Request.Builder()
                                     .url(httpBuilder.build())
                                     .header("Authorization", "Bearer " + CLIENT_ACCESS_TOKEN)
                                     .header("Content-Type", "audio/raw")
                                     .header("Transfer-Encoding", "chunked");
    }

    // Initialize the Android TextToSpeech
    // https://developer.android.com/reference/android/speech/tts/TextToSpeech
    private void initializeTextToSpeech(Context applicationContext) {
        textToSpeech = new TextToSpeech(applicationContext, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int ttsStatus) {
                // Disable the speakButton and provide the status of app while waiting for TextToSpeech to initialize
                speechTranscription.setHint("Loading app ...");
                speakButton.setEnabled(false);

                // Check the status of the initialization
                if(ttsStatus == TextToSpeech.SUCCESS) {
                    if(CLIENT_ACCESS_TOKEN == "<YOUR CLIENT ACCESS TOKEN>") {
                        textToSpeech.speak("Hi! Before we start the demo. Please set the client access token.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                    } else {
                        textToSpeech.speak("Hi, I am azure, how may I assist blu?", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                    }
                    speechTranscription.setHint("Press Speak and say something!");
                    speakButton.setEnabled(true);
                } else {
                    displayErrorMessage();
                }
            }
        });
    }

    private void displayErrorMessage() {
        String message = "TextToSpeech initialization failed";
        speechTranscription.setTextColor(Color.RED);
        speechTranscription.setText(message);
        Log.e("TextToSpeech", message);
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.INTERNET, Manifest.permission.RECORD_AUDIO, Manifest.permission.SET_ALARM}, 1000);
    }

    private boolean checkPermissionsFromDevice() {
        int recordAudioResult = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        int internetResult = ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET);

        return recordAudioResult == PackageManager.PERMISSION_GRANTED && internetResult == PackageManager.PERMISSION_GRANTED;
    }
}

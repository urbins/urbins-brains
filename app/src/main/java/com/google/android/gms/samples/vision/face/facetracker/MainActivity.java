/*
 * Copyright (C) The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.gms.samples.vision.face.facetracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.support.constraint.ConstraintLayout;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Base64;
import android.util.Log;
import android.view.View;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.samples.vision.face.facetracker.ui.camera.CameraSource;
import com.google.android.gms.samples.vision.face.facetracker.ui.camera.CameraSourcePreview;
import com.google.android.gms.samples.vision.face.facetracker.ui.camera.GraphicOverlay;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.FaceDetector;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jonahchin.urbins.R;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

import static android.hardware.Camera.Parameters.FLASH_MODE_TORCH;

/**
 * Activity for the face tracker app.  This app detects faces with the rear facing camera, and draws
 * overlay graphics to indicate the position, size, and ID of each face.
 */
public final class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private CameraSource mCameraSource = null;
    private CameraSourcePreview mPreview;
    private static final int RC_HANDLE_GMS = 9001;
    private static final int RC_HANDLE_CAMERA_PERM = 2;

    private ConstraintLayout mWaitingBox;
    private TextToSpeech mSpeechObject;
    DatabaseReference mDatabase = FirebaseDatabase.getInstance().getReference();
    DatabaseReference mObjectSensed = mDatabase.child("object-sensed");
    DatabaseReference status = mDatabase.child("status");
    DatabaseReference mGarbageStatus = status.child("garbage");
    DatabaseReference mRecycleStatus = status.child("recycle");
    DatabaseReference mTypeSensed = status.child("type");

    StorageReference mStorageRef = FirebaseStorage.getInstance().getReference();

    boolean mGarbageTexted;
    boolean mRecycleTexted;

    private static final String WATSON_ENDPOINT = "https://gateway-a.watsonplatform.net/visual-recognition/api/v3/classify?api_key=9adf45f9ae3b9685ed1f352a026b64cb04a795fd&owners=me&version=2016-05-20";
    private static final String STD_TEXT = "https://urbins.lib.id/urbins-text@dev/?tel=2042509218&text=";
    private static final String STD_SLACK = "https://urbins.lib.id/slack-app@dev/ask/?img=";

    /**
     * Initializes the UI and initiates the creation of a face detector.
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.main_constraint);

        mPreview = (CameraSourcePreview) findViewById(R.id.preview);
        mWaitingBox = (ConstraintLayout) findViewById(R.id.waiting_box);

        mGarbageTexted = false;
        mRecycleTexted = false;

        // Check for the camera permission before accessing the camera.  If the
        // permission is not granted yet, request permission.
        int rc = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if (rc == PackageManager.PERMISSION_GRANTED) {
            createCameraSource();
        } else {
            requestCameraPermission();
        }

        initializeServices();
//        waitForItem();

        //DEBUG CAMERA
        startCameraSource();
        mPreview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                takePhotoOfObject();
            }
        });

    }

    private void initializeServices() {
        mSpeechObject = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if(status != TextToSpeech.ERROR){
                    mSpeechObject.setLanguage(Locale.UK);
                    Log.i(TAG, "TTS initialized");
                }
            }
        });

    }

    private void waitForItem() {

        mObjectSensed.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if((boolean) dataSnapshot.getValue()) {
                    Log.i(TAG, "Turning on camera...");
                    mObjectSensed.setValue(false);
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mSpeechObject.speak("Thanks for the trash! Sorting now.", TextToSpeech.QUEUE_ADD, null, "item_sensed");
                            startCameraSource();
                        }
                    }, 1000);

                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });

        mGarbageStatus.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if(Double.parseDouble(dataSnapshot.getValue().toString()) >= 85 && !mGarbageTexted){
                    mSpeechObject.speak("Garbage is getting full!", TextToSpeech.QUEUE_ADD, null, "garbage_full");
                    callSTDLib(false);
                    mGarbageTexted = true;
                }else if(Double.parseDouble(dataSnapshot.getValue().toString()) < 5 && mGarbageTexted){
                    mGarbageTexted = false;
                    mSpeechObject.speak("Thanks for collecting the trash!", TextToSpeech.QUEUE_ADD, null, "trash_collected");
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });

        mRecycleStatus.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if(Double.parseDouble(dataSnapshot.getValue().toString()) >= 85 && !mRecycleTexted){
                    mSpeechObject.speak("Recycling is getting full!", TextToSpeech.QUEUE_ADD, null, "recycle_full");
                    callSTDLib(true);
                    mRecycleTexted = true;
                }else if(Double.parseDouble(dataSnapshot.getValue().toString()) < 5 && mRecycleTexted){
                    mRecycleTexted = false;
                    mSpeechObject.speak("Thanks for collecting the recycling!", TextToSpeech.QUEUE_ADD, null, "recycle_collected");
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    private void callSTDLib(boolean recycling) {
        String message;
        if(recycling)
            message = "Recycling%20at%20ILC%20is%20full!";
        else
            message = "Garbage%20at%20ILC%20is%20full!";

        Log.i(TAG, message);

        Ion.with(getApplicationContext())
                .load(STD_TEXT + message)
                .asJsonObject()
                .setCallback(new FutureCallback<JsonObject>() {
                    @Override
                    public void onCompleted(Exception e, JsonObject result) {
                        Log.i(TAG, "Text sent");
                    }
                });
    }

    private void takePhotoOfObject() {
        mCameraSource.takePicture(new CameraSource.ShutterCallback() {
            @Override
            public void onShutter() {

            }
        }, new CameraSource.PictureCallback() {

            @Override
            public void onPictureTaken(final byte[] bytes) {
                new AsyncTask<Void, Void, String>() {
                    @Override
                    protected String doInBackground(Void... params) {
                        return Base64.encodeToString(bytes, 0);
                    }

                    @Override
                    protected void onPostExecute(String bitmap) {
                        Log.i(TAG, "IMAGE CAPTURED");
                        mSpeechObject.speak("Checking item type",TextToSpeech.QUEUE_ADD, null, "snapping photo");

                        FileOutputStream fos;
                        File tempFile = null;
                        try {
                            tempFile = File.createTempFile("pre", "suf", null);
                            fos = new FileOutputStream(tempFile);
                            fos.write(bytes);

                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        Bitmap bmp = BitmapFactory.decodeByteArray(bytes , 0, bytes.length);
                        ByteArrayOutputStream stream = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.JPEG, 40, stream);
                        final byte[] byteArray = stream.toByteArray();

                        final String title = Long.toString(System.currentTimeMillis());

                        Ion.with(getApplicationContext())
                                .load(WATSON_ENDPOINT)
                                .setMultipartFile("images_file", tempFile)
                                .asString()
                                .setCallback(new FutureCallback<String>() {
                                    @Override
                                    public void onCompleted(Exception e, String result) {
                                        try {
                                            JSONObject jsonObject = new JSONObject(result);
                                            Log.e(TAG, result);
                                            JSONObject temp = (JSONObject) jsonObject.getJSONArray("images").get(0);
                                            JSONArray classifiers= temp.getJSONArray("classifiers");


                                            if(classifiers.length() > 0){

                                                JSONObject classifier = (JSONObject) classifiers.get(0);
                                                JSONObject firstClass = (JSONObject) classifier.getJSONArray("classes").get(0);
                                                String className = firstClass.getString("class");

                                                if(className.equalsIgnoreCase("recycle")){
                                                    mSpeechObject.speak("This item is recycling... Thank you!", TextToSpeech.QUEUE_ADD, null, "recycling_added");
                                                    mTypeSensed.setValue(2);
                                                }else if(className.equalsIgnoreCase("garbage")) {
                                                    mSpeechObject.speak("Placing item in garbage... Thanks!", TextToSpeech.QUEUE_ADD, null, "garbage_added");
                                                    mTypeSensed.setValue(1);
                                                }
                                            }else{
                                                mSpeechObject.speak("We could not detect your item type... we are notifying the city!", TextToSpeech.QUEUE_ADD, null, "notify_city");

                                                mStorageRef.child(title).putBytes(byteArray).addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
                                                    @Override
                                                    public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
                                                        @SuppressWarnings("VisibleForTests") Uri downloadUrl = taskSnapshot.getDownloadUrl();
                                                        Log.e(TAG, "Image uploaded to: " + downloadUrl);

                                                        Ion.with(getApplicationContext())
                                                                .load(STD_SLACK + downloadUrl)
                                                                .asJsonObject()
                                                                .setCallback(new FutureCallback<JsonObject>() {
                                                                    @Override
                                                                    public void onCompleted(Exception e, JsonObject result) {
                                                                        Log.e(TAG, "Image sent: " + result);
                                                                    }
                                                                });
                                                    }
                                                });
                                            }
                                        } catch (JSONException e1) {
                                            e1.printStackTrace();
                                        }

                                    }
                                });



                    }
                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);




                stopCamera();
            }
        });


    }

    private void stopCamera() {
        mPreview.stop();
        mWaitingBox.setVisibility(View.VISIBLE);
    }

    /**
     * Handles the requesting of the camera permission.  This includes
     * showing a "Snackbar" message of why the permission is needed then
     * sending the request.
     */
    private void requestCameraPermission() {
        Log.w(TAG, "Camera permission is not granted. Requesting permission");

        final String[] permissions = new String[]{Manifest.permission.CAMERA};

        if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM);
            return;
        }

        final Activity thisActivity = this;

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ActivityCompat.requestPermissions(thisActivity, permissions,
                        RC_HANDLE_CAMERA_PERM);
            }
        };
    }

    /**
     * Creates and starts the camera.  Note that this uses a higher resolution in comparison
     * to other detection examples to enable the barcode detector to detect small barcodes
     * at long distances.
     */
    private void createCameraSource() {

        Context context = getApplicationContext();
        FaceDetector detector = new FaceDetector.Builder(context)
                .setClassificationType(FaceDetector.ALL_CLASSIFICATIONS)
                .build();

        detector.setProcessor(
                new MultiProcessor.Builder<>(new GraphicFaceTrackerFactory())
                        .build());

        if (!detector.isOperational()) {
            Log.w(TAG, "Face detector dependencies are not yet available.");
        }

        mCameraSource = new CameraSource.Builder(context, detector)
                .setRequestedPreviewSize(640, 480)
                .setFlashMode(FLASH_MODE_TORCH)
                .setFacing(CameraSource.CAMERA_FACING_BACK)
                .setRequestedFps(30.0f)
                .build();

    }

    /**
     * Restarts the camera.
     */
    @Override
    protected void onResume() {
        super.onResume();
//        startCameraSource();
    }

    /**
     * Stops the camera.
     */
    @Override
    protected void onPause() {
        super.onPause();
        mPreview.stop();
    }

    /**
     * Releases the resources associated with the camera source, the associated detector, and the
     * rest of the processing pipeline.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCameraSource != null) {
            mCameraSource.release();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != RC_HANDLE_CAMERA_PERM) {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission granted - initialize the camera source");
            // we have permission, so create the camerasource
            createCameraSource();
            return;
        }

        Log.e(TAG, "Permission not granted: results len = " + grantResults.length +
                " Result code = " + (grantResults.length > 0 ? grantResults[0] : "(empty)"));

        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                finish();
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Face Tracker sample")
                .setMessage(R.string.no_camera_permission)
                .setPositiveButton(R.string.ok, listener)
                .show();
    }

    //==============================================================================================
    // Camera Source Preview
    //==============================================================================================

    /**
     * Starts or restarts the camera source, if it exists.  If the camera source doesn't exist yet
     * (e.g., because onResume was called before the camera source was created), this will be called
     * again when the camera source is created.
     */
    private void startCameraSource() {

        // check that the device has play services available.
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                getApplicationContext());
        if (code != ConnectionResult.SUCCESS) {
            Dialog dlg =
                    GoogleApiAvailability.getInstance().getErrorDialog(this, code, RC_HANDLE_GMS);
            dlg.show();
        }

        if (mCameraSource != null) {
            try {
                mPreview.start(mCameraSource);
                mWaitingBox.setVisibility(View.INVISIBLE);
                takePhotoOfObject();
            } catch (IOException e) {
                Log.e(TAG, "Unable to start camera source.", e);
                mCameraSource.release();
                mCameraSource = null;
            }
        }
    }



    //==============================================================================================
    // Graphic Face Tracker
    //==============================================================================================

    /**
     * Factory for creating a face tracker to be associated with a new face.  The multiprocessor
     * uses this factory to create face trackers as needed -- one for each individual.
     */
    private class GraphicFaceTrackerFactory implements MultiProcessor.Factory<Face> {
        @Override
        public Tracker<Face> create(Face face) {
            return new GraphicFaceTracker(new GraphicOverlay(getApplicationContext(), null));
        }
    }

    /**
     * Face tracker for each detected individual. This maintains a face graphic within the app's
     * associated face overlay.
     */
    private class GraphicFaceTracker extends Tracker<Face> {
        private GraphicOverlay mOverlay;
        GraphicFaceTracker(GraphicOverlay overlay) {
            mOverlay = overlay;
        }

    }
}

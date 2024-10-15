package com.example.tryagain;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.face.LBPHFaceRecognizer;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ImageSelectionActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int PICK_IMAGE_REQUEST = 101;
    RecyclerView recyclerView;
    private CascadeClassifier faceDetector;



    @SuppressLint({"ResourceType", "MissingInflatedId"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_selection);


        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 3)); // 3 columns in grid

        Button button_make_faces= findViewById(R.id.button_make_faces);
        button_make_faces.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                initializeOpenCVDependencies();
                processAndSaveFaces();
            }
        });



        Button selectImageButton = findViewById(R.id.button_select_image);
        selectImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ContextCompat.checkSelfPermission(ImageSelectionActivity.this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(ImageSelectionActivity.this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
                } else {
                    openGallery();
                }
            }
        });

        Button showImageButton = findViewById(R.id.button_show_image);
        showImageButton.setOnClickListener(v -> showImage());
        Button trainModel = findViewById(R.id.button_train);
        trainModel.setOnClickListener(v -> trainFaceRecognizer());
        Button deleteButton = findViewById(R.id.button_delete);
        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteSelectedImages();
            }
        });
    }


    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }



    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openGallery();
            } else {
                Toast.makeText(this, "Permission is required to access the gallery.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK) {
            if (data.getClipData() != null) {
                ClipData clipData = data.getClipData();
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    Uri imageUri = clipData.getItemAt(i).getUri();
                    saveImageToAppFolder(imageUri, "selectedImage_" + i + ".jpg");

                }
            } else if (data.getData() != null) {
                Uri imageUri = data.getData();
                saveImageToAppFolder(imageUri, "selectedImage.jpg");

            }
        }
    }




    private void showImage() {
        updateImageGrid(); // Refresh the image grid

    }

    private void deleteSelectedImages() {
        List<File> selectedFiles = ((ImageAdapter)recyclerView.getAdapter()).getSelectedItems();
        for (File file : selectedFiles) {
            if (file.exists()) {
                boolean deleted = file.delete();
                if (!deleted) {
                    Log.e("ImageSelectionActivity", "Failed to delete file: " + file.getAbsolutePath());
                }
            }
        }
        updateImageGrid();
        ((ImageAdapter)recyclerView.getAdapter()).clearSelection();
    }
    private void saveImageToAppFolder(Uri imageUri, String filename) {
        File appFolder = new File(getFilesDir(), "FaceRecognitionImages");
        if (!appFolder.exists()) {
            appFolder.mkdirs();
        }

        try {
            Bitmap bitmap = getCorrectlyOrientedBitmap(imageUri);
            File destination = new File(appFolder, filename);

            try (OutputStream out = new FileOutputStream(destination)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(this, "Error saving image", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show();
        }
    }


    private Bitmap getCorrectlyOrientedBitmap(Uri imageUri) throws IOException {
        InputStream in = getContentResolver().openInputStream(imageUri);
        Bitmap bitmap = BitmapFactory.decodeStream(in);

        in = getContentResolver().openInputStream(imageUri);
        ExifInterface exifInterface = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            exifInterface = new ExifInterface(in);
        }
        int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        in.close();

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }



    private String getRealPathFromURI(Uri contentUri) {
        Cursor cursor = null;
        try {
            String[] proj = { MediaStore.Images.Media.DATA };
            cursor = getContentResolver().query(contentUri, proj, null, null, null);
            int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            cursor.moveToFirst();
            return cursor.getString(column_index);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
    private void updateImageGrid() {
        File appFolder = new File(getFilesDir(), "CroppedFaces");
        File[] imageFiles = appFolder.listFiles();
        List<File> fileList = imageFiles != null ? Arrays.asList(imageFiles) : new ArrayList<>();
        ImageAdapter adapter = (ImageAdapter) recyclerView.getAdapter();
        if (adapter != null) {
            adapter.setImageFiles(fileList);
            adapter.notifyDataSetChanged(); // Refresh adapter
        } else {
            adapter = new ImageAdapter(this, fileList);
            recyclerView.setAdapter(adapter);
        }
    }


    private void initializeOpenCVDependencies() {
        try {
            InputStream is = getResources().openRawResource(R.raw.haarcascade_frontalface_alt2);
            File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
            File mCascadeFile = new File(cascadeDir, "haarcascade_frontalface_alt2.xml");
            FileOutputStream os = new FileOutputStream(mCascadeFile);

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            is.close();
            os.close();

            faceDetector = new CascadeClassifier(mCascadeFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e("OpenCVActivity", "Error loading cascade", e);
        }
    }

    private void processAndSaveFaces() {
        File imagesFolder = new File(getFilesDir(), "FaceRecognitionImages");
        File[] imageFiles = imagesFolder.listFiles();

        File croppedFacesFolder = new File(getFilesDir(), "CroppedFaces");
        if (!croppedFacesFolder.exists()) {
            croppedFacesFolder.mkdirs();
        }

        for (File imageFile : imageFiles) {
            Bitmap imageBitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
            Mat image = new Mat();
            Utils.bitmapToMat(imageBitmap, image);

            MatOfRect faceDetections = new MatOfRect();
            faceDetector.detectMultiScale(image, faceDetections);

            for (Rect rect : faceDetections.toArray()) {
                Mat croppedFace = new Mat(image, rect);
                Imgproc.cvtColor(croppedFace, croppedFace, Imgproc.COLOR_BGR2RGB);
                File croppedImageFile = new File(croppedFacesFolder, "cropped_face_" + System.currentTimeMillis() + ".jpg");
                Imgcodecs.imwrite(croppedImageFile.getAbsolutePath(), croppedFace);
            }
        }
        Toast.makeText(getApplicationContext(), "DONE WITH FACES", Toast.LENGTH_SHORT).show();
    }

    public void trainFaceRecognizer() {
        File croppedFacesFolder = new File(getFilesDir(), "CroppedFaces");
        File[] croppedFaceFiles = croppedFacesFolder.listFiles();

        if (croppedFaceFiles != null && croppedFaceFiles.length > 0) {
            List<Mat> faceImages = new ArrayList<>();
            List<Integer> labels = new ArrayList<>();

            int label = 0;

            for (File faceFile : croppedFaceFiles) {
                Bitmap faceBitmap = BitmapFactory.decodeFile(faceFile.getAbsolutePath());
                Mat faceMat = new Mat();
                Utils.bitmapToMat(faceBitmap, faceMat);

                Imgproc.cvtColor(faceMat, faceMat, Imgproc.COLOR_RGB2GRAY);

                faceImages.add(faceMat);
                labels.add(label);
            }

            Mat[] faceImagesArray = faceImages.toArray(new Mat[0]);
            int[] labelsArray = new int[labels.size()];
            for (int i = 0; i < labels.size(); i++) {
                labelsArray[i] = labels.get(i);
            }

            LBPHFaceRecognizer lbphFaceRecognizer = LBPHFaceRecognizer.create();

            lbphFaceRecognizer.train(Arrays.asList(faceImagesArray), new MatOfInt(labelsArray));

            String modelPath = getFilesDir() + "/lbph_face_recognizer_model.xml";
            lbphFaceRecognizer.save(modelPath);

            Log.d("FaceRecognizer", "Model saved at: " + modelPath);
        }
    }




}
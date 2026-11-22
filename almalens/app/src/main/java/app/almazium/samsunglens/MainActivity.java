package app.almazium.samsunglens;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String SAMSUNG_CAMERA = "com.sec.android.app.camera";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.open_camera).setOnClickListener(view -> openCamera());
    }

    private void openCamera() {
        Intent camera = getPackageManager().getLaunchIntentForPackage(SAMSUNG_CAMERA);
        if (camera == null) {
            camera = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).setPackage(SAMSUNG_CAMERA);
        }

        try {
            startActivity(camera);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show();
        }
    }
}

package beaconiq.sensor;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class OrientationSensor implements SensorEventListener {

    public interface OrientationListener {
        void onOrientationChanged(float azimuthDeg, float pitchDeg, float rollDeg);
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_ROTATION_VECTOR = 1;
    private static final int MODE_GEOMAGNETIC = 2;
    private static final int MODE_ACCEL_MAG = 3;
    private static final int MODE_ACCEL_ONLY = 4;

    private SensorManager sensorManager;
    private int activeMode = MODE_NONE;
    private OrientationListener listener;
    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];
    private float[] lastAccel;
    private float[] lastMag;

    public void setListener(OrientationListener listener) {
        this.listener = listener;
    }

    public void start(Activity activity) {
        sensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) return;

        Sensor rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotation != null) {
            sensorManager.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI);
            activeMode = MODE_ROTATION_VECTOR;
            return;
        }

        Sensor geomagnetic = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        if (geomagnetic != null) {
            sensorManager.registerListener(this, geomagnetic, SensorManager.SENSOR_DELAY_UI);
            activeMode = MODE_GEOMAGNETIC;
            return;
        }

        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (accel != null && mag != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI);
            sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI);
            activeMode = MODE_ACCEL_MAG;
            return;
        }

        if (accel != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI);
            activeMode = MODE_ACCEL_ONLY;
        }
    }

    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        lastAccel = null;
        lastMag = null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (activeMode) {
            case MODE_ROTATION_VECTOR:
                if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                SensorManager.getOrientation(rotationMatrix, orientationAngles);
                notifyOrientation();
                break;

            case MODE_GEOMAGNETIC:
                if (event.sensor.getType() != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) return;
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                SensorManager.getOrientation(rotationMatrix, orientationAngles);
                notifyOrientation();
                break;

            case MODE_ACCEL_MAG:
                if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                    lastAccel = event.values.clone();
                } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                    lastMag = event.values.clone();
                }
                if (lastAccel != null && lastMag != null) {
                    if (SensorManager.getRotationMatrix(rotationMatrix, null, lastAccel, lastMag)) {
                        SensorManager.getOrientation(rotationMatrix, orientationAngles);
                        notifyOrientation();
                    }
                }
                break;

            case MODE_ACCEL_ONLY:
                if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
                float ax = event.values[0];
                float ay = event.values[1];
                float az = event.values[2];
                orientationAngles[0] = 0;
                orientationAngles[1] = (float) Math.atan2(-ax, Math.sqrt(ay * ay + az * az));
                orientationAngles[2] = (float) Math.atan2(ay, az);
                notifyOrientation();
                break;
        }
    }

    private void notifyOrientation() {
        float azimuth = (float) Math.toDegrees(orientationAngles[0]);
        if (azimuth < 0) azimuth += 360f;
        float pitch = (float) Math.toDegrees(orientationAngles[1]);
        float roll = (float) Math.toDegrees(orientationAngles[2]);
        if (listener != null) {
            listener.onOrientationChanged(azimuth, pitch, roll);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}

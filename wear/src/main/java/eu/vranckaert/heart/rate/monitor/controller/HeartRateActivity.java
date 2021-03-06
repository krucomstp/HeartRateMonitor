package eu.vranckaert.heart.rate.monitor.controller;

import android.Manifest.permission;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.support.wearable.activity.WearableActivity;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import eu.vranckaert.heart.rate.monitor.R;
import eu.vranckaert.heart.rate.monitor.WearUserPreferences;
import eu.vranckaert.heart.rate.monitor.controller.ConfigObserver.ConfigObservable;
import eu.vranckaert.heart.rate.monitor.controller.HeartRateObserver.HeartRateObservable;
import eu.vranckaert.heart.rate.monitor.service.AlarmSchedulingService;
import eu.vranckaert.heart.rate.monitor.shared.dao.IMeasurementDao;
import eu.vranckaert.heart.rate.monitor.shared.dao.MeasurementDao;
import eu.vranckaert.heart.rate.monitor.shared.model.Measurement;
import eu.vranckaert.heart.rate.monitor.shared.permission.PermissionUtil;
import eu.vranckaert.heart.rate.monitor.task.HeartRateMeasurementTask;
import eu.vranckaert.heart.rate.monitor.task.HeartRateMeasurementTask.HeartRateMeasurementTaskListener;
import eu.vranckaert.heart.rate.monitor.util.DeviceUtil;
import eu.vranckaert.heart.rate.monitor.view.AbstractViewHolder;
import eu.vranckaert.heart.rate.monitor.view.GooglePlayServicesIssueView;
import eu.vranckaert.heart.rate.monitor.view.HeartRateBodySensorPermissionDenied;
import eu.vranckaert.heart.rate.monitor.view.HeartRateBodySensorPermissionDenied.HeartRateBodySensorPermissionDeniedListener;
import eu.vranckaert.heart.rate.monitor.view.HeartRateHistoryView;
import eu.vranckaert.heart.rate.monitor.view.HeartRateMonitorView;
import eu.vranckaert.heart.rate.monitor.view.HeartRateSetupView;
import eu.vranckaert.heart.rate.monitor.view.HeartRateUnavailableView;
import eu.vranckaert.heart.rate.monitor.view.HeartRateView;
import eu.vranckaert.heart.rate.monitor.view.HeartRateView.HeartRateListener;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Date: 28/05/15
 * Time: 08:03
 *
 * @author Dirk Vranckaert
 */
public class HeartRateActivity extends WearableActivity implements SensorEventListener, HeartRateListener,
        HeartRateMeasurementTaskListener, HeartRateObservable, ConfigObservable,
        HeartRateBodySensorPermissionDeniedListener {
    private static final int REQUEST_CODE_PERMISSION_BODY_SENSOR = 0;

    private HeartRateView mView;

    private SensorManager mSensorManager;
    private Sensor mHeartRateSensor;
    private boolean mMeasuring;
    private long mStartTimeMeasurement;
    private boolean mFirstValueFound;
    private Map<Long, Float> mMeasuredValues;
    private long mFirstMeasurement = -1;
    private float mMaximumHeartBeat = -1;
    private float mMinimumHeartBeat = -1;

    private HeartRateMonitorView mMonitorView;
    private HeartRateHistoryView mHistoryView;
    private boolean mInputLocked;
    private Toast mPhoneSyncToast;

    // TODO start using the maximum heart rate (MHR): http://www.calculatenow.biz/sport/heart.php?age=28&submit=Calculate+MHR#mhr
    // This is based on the users age (below 30 or above 30). If average measured heart beat is significantly higher than
    // the MHR we could notify the user

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initView(true);
    }

    private void initView(boolean askForPermission) {
        if (getSystemService(Context.SENSOR_SERVICE) != null) {
            mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

            boolean hasPermission = PermissionUtil.hasPermission(this, permission.BODY_SENSORS);
            if (hasPermission) {
                if (mSensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null) {
                    mHeartRateSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);

                    int googlePlayServicesCheck = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
                    if (googlePlayServicesCheck == ConnectionResult.SUCCESS) {
                        boolean phoneSetupCompleted = WearUserPreferences.getInstance().isPhoneSetupCompleted();
                        if (phoneSetupCompleted) {
                            setAmbientEnabled();
                            if (mView == null) {
                                mView = new HeartRateView(this, this);
                            }
                            setContentView(mView.getView());

                            if (!WearUserPreferences.getInstance().hasRunBefore()) {
                                WearUserPreferences.getInstance().setHasRunBefore();
                                SetupBroadcastReceiver.setupMeasuring(this);
                            }
                        } else {
                            phoneSetupNotYetCompleted();
                        }
                    } else {
                        googlePlayServicesNotSupported(googlePlayServicesCheck);
                    }
                } else {
                    heartRateSensorNotSupported();
                }
            } else {
                heartRatePermissionDenied();
                if (askForPermission) {
                    PermissionUtil.requestPermission(this, REQUEST_CODE_PERMISSION_BODY_SENSOR, permission.BODY_SENSORS, null);
                }
            }
        } else {
            heartRateSensorNotSupported();
        }
    }

    private void googlePlayServicesNotSupported(int errorCode) {
        GooglePlayServicesUtil.showErrorNotification(errorCode, this);
        setContentView(new GooglePlayServicesIssueView(this).getView());
    }

    private void heartRateSensorNotSupported() {
        setContentView(new HeartRateUnavailableView(this).getView());
    }

    private void heartRatePermissionDenied() {
        boolean canShowPermissionRequest = PermissionUtil.shouldShowRequestPermissionRationale(this, permission.BODY_SENSORS);
        setContentView(new HeartRateBodySensorPermissionDenied(this, this, canShowPermissionRequest).getView());
    }

    private void phoneSetupNotYetCompleted() {
        setContentView(new HeartRateSetupView(this).getView());
    }

    @Override
    public void onEnterAmbient(Bundle ambientDetails) {
        Log.d("dirk", "onEnterAmbient");
        Log.d("dirk", "isAmbient=" + isAmbient());
        if (mView != null) {
            mView.startAmbientMode();
        } else {
            finish();
        }

        super.onEnterAmbient(ambientDetails);
    }

    @Override
    public void onExitAmbient() {
        Log.d("dirk", "onExitAmbient");
        Log.d("dirk", "isAmbient=" + isAmbient());
        if (mView != null) {
            mView.stopAmbientMode();
        } else {
            finish();
        }

        super.onExitAmbient();
    }

    @Override
    public void onUpdateAmbient() {
        Log.d("dirk", "onUpdateAmbient");
        Log.d("dirk", "isAmbient=" + isAmbient());
        mView.updateInAmbient();

        super.onUpdateAmbient();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        Log.d("dirk", "onSensorChanged");
        for (int i = 0; i < event.values.length; i++) {
            float value = event.values[i];
            Log.d("dirk", "event.values[i] = " + value);
        }

        if (DeviceUtil.isCharging()) {
            // Silently stop measuring as the device is charging
            clearMeasuredValues();
            mMinimumHeartBeat = -1;
            mMaximumHeartBeat = -1;
            stopHearRateMonitor();
            loadHistoricalData();
            return;
        }
        if (event.values.length > 0) {
            float value = event.values[event.values.length - 1];
            if (mFirstValueFound || value > 0f) {
                long currentTime = new Date().getTime();
                mFirstValueFound = true;
                if (mFirstMeasurement == -1) {
                    mFirstMeasurement = currentTime;
                }
                addMeasuredValue(currentTime, value);
                if (mMinimumHeartBeat == -1 || value < mMinimumHeartBeat) {
                    mMinimumHeartBeat = value;
                }
                if (mMaximumHeartBeat == -1 || value > mMaximumHeartBeat) {
                    mMaximumHeartBeat = value;
                }
                mMonitorView.setMeasuringHeartBeat((int) value);
            }
        }

    }

    private void addMeasuredValue(long currentTime, float value) {
        if (mMeasuredValues == null) {
            clearMeasuredValues();
        }
        mMeasuredValues.put(currentTime, value);
    }

    private void clearMeasuredValues() {
        mMeasuredValues = new HashMap<>();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d("dirk", "onAccuracyChanged:" + accuracy);
    }

    @Override
    protected void onDestroy() {
        stopHearRateMonitor();
        super.onDestroy();
    }

    private void startHearRateMonitor() {
        mFirstValueFound = false;
        clearMeasuredValues();
        mMinimumHeartBeat = -1;
        mMaximumHeartBeat = -1;
        if (mSensorManager != null && mHeartRateSensor != null) {
            mSensorManager.registerListener(this, mHeartRateSensor, SensorManager.SENSOR_DELAY_FASTEST);
            mMeasuring = true;
            mStartTimeMeasurement = new Date().getTime();
        }
    }

    private void stopHearRateMonitor() {
        mMeasuring = false;
        if (mSensorManager != null && mHeartRateSensor != null) {
            mSensorManager.unregisterListener(this, mHeartRateSensor);
        }

        if (mMeasuredValues != null && !mMeasuredValues.isEmpty()) {
            final float averageHeartBeat = calculateAverageHeartBeat();
            Measurement measurement = new Measurement();
            measurement.updateUniqueKey();
            measurement.setAverageHeartBeat(averageHeartBeat);
            measurement.setMinimumHeartBeat(mMinimumHeartBeat);
            measurement.setMaximumHeartBeat(mMaximumHeartBeat);
            measurement.setStartMeasurement(mStartTimeMeasurement);
            measurement.setEndMeasurement(new Date().getTime());
            measurement.setFirstMeasurement(mFirstMeasurement);
            measurement.setMeasuredValues(mMeasuredValues);

            IMeasurementDao measurementDao = new MeasurementDao(this);
            measurementDao.save(measurement);

            new HeartRateMeasurementTask().execute(measurementDao.findMeasurementsToSyncWithPhone());
            mMeasuredValues = null;
        }
    }

    private float calculateAverageHeartBeat() {
        Log.d("dirk", "calculateAverageHeartBeat");
        Log.d("dirk", "mMeasuredValues.size=" + mMeasuredValues.size());

        float sum = 0f;
        for (Entry<Long, Float> entry : mMeasuredValues.entrySet()) {
            float measuredValue = entry.getValue();
            sum += measuredValue;
        }

        float averageHearBeat = sum / mMeasuredValues.size();
        Log.d("dirk", "averageHearBeat=" + averageHearBeat);
        return averageHearBeat;
    }

    @Override
    public void onHearRateViewCreated(AbstractViewHolder view) {
        if (view instanceof HeartRateMonitorView) {
            mMonitorView = (HeartRateMonitorView) view;
        } else if (view instanceof HeartRateHistoryView) {
            mHistoryView = (HeartRateHistoryView) view;
        }

        if (mMonitorView != null && mHistoryView != null) {
            loadHistoricalData();
        }
    }

    private void loadHistoricalData() {
        IMeasurementDao measurementDao = new MeasurementDao(this);
        Measurement latestMeasurement = measurementDao.findLatest();
        if (latestMeasurement != null) {
            mMonitorView.setLatestMeasurement(latestMeasurement);
            mHistoryView.setMeasurements(measurementDao.findAllSorted());
        }
    }

    @Override
    public View getBoxInsetReferenceView() {
        return mView.getBoxInsetReferenceView();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (permissions[0].equals(permission.BODY_SENSORS) && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            AlarmSchedulingService.getInstance().rescheduleHeartRateMeasuringAlarms(this);
        }
        initView(false);
    }

    @Override
    public boolean toggleHeartRateMonitor() {
        if (!PermissionUtil
                .requestPermission(this, REQUEST_CODE_PERMISSION_BODY_SENSOR, permission.BODY_SENSORS, null)) {
            return false;
        }

        if (!mInputLocked) {
            mInputLocked = true;
            if (!mMeasuring) {
                startHearRateMonitor();
            } else {
                stopHearRateMonitor();
                loadHistoricalData();
            }
            mInputLocked = false;
        }
        return mMeasuring;
    }

    @Override
    public void openSettings() {
        Intent intent = new Intent(this, HeartRateSettingsActivity.class);
        startActivity(intent);
    }

    @Override
    public void onItemSelected(Measurement measurement) {
        List<Measurement> measurements = new ArrayList<>();
        measurements.add(measurement);
        new HeartRateMeasurementTask(this).execute(measurements);
    }

    @Override
    public void beforeSync() {
        if (mPhoneSyncToast != null) {
            mPhoneSyncToast.cancel();
        }
        mPhoneSyncToast = Toast.makeText(this, R.string.heart_rate_manual_sync_start, Toast.LENGTH_SHORT);
        mPhoneSyncToast.show();
    }

    @Override
    public void afterSync(boolean success) {
        if (mPhoneSyncToast != null) {
            mPhoneSyncToast.cancel();
        }
        mPhoneSyncToast =
                Toast.makeText(this, success ? R.string.heart_rate_manual_sync_finished_success :
                        R.string.heart_rate_manual_sync_finished_failure, Toast.LENGTH_SHORT);
        mPhoneSyncToast.show();
    }

    @Override
    protected void onResume() {
        super.onResume();

        HeartRateObserver.register(this);
        ConfigObserver.register(this);
    }

    @Override
    protected void onPause() {
        HeartRateObserver.unregister(this);
        ConfigObserver.unregister(this);

        super.onPause();
    }

    @Override
    public void onHeartBeatMeasured(float bpm) {
        mMonitorView.followingHeartBeat((int) bpm);
    }

    @Override
    public void onStartMeasuringHeartBeat() {
        mMonitorView.startFollowingHeartBeat();
    }

    @Override
    public void onStopMeasuringHeartBeat() {
        mMonitorView.stopFollowingHeartBeat();
        loadHistoricalData();
    }

    @Override
    public void onHeartRateMeasurementsSentToPhone() {
        loadHistoricalData();
    }

    @Override
    public void onMeasurementsAckReceived() {
        loadHistoricalData();
    }

    @Override
    public void onPhoneSetupCompletionChanged() {
        initView(true);
    }

    @Override
    public void grantBodySensorPermission() {
        PermissionUtil.requestPermission(this, REQUEST_CODE_PERMISSION_BODY_SENSOR, permission.BODY_SENSORS, null);
    }
}

package eu.faircode.backpacktrack2;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.timroes.axmlrpc.XMLRPCClient;

public class LocationService extends IntentService {
    private static final String TAG = "BPT2.Service";

    // Actions
    public static final String ACTION_ALARM = "Alarm";
    public static final String ACTION_ACTIVITY = "Activity";
    public static final String ACTION_LOCATION_FINE = "LocationFine";
    public static final String ACTION_LOCATION_COARSE = "LocationCoarse";
    public static final String ACTION_TIMEOUT = "TimeOut";
    public static final String ACTION_TRACKPOINT = "TrackPoint";
    public static final String ACTION_WAYPOINT = "WayPoint";
    public static final String ACTION_GEOTAGGED = "Geotagged";
    public static final String ACTION_SHARE = "Share";
    public static final String ACTION_UPLOAD = "Upload";

    // Extras
    public static final String EXTRA_TRACK = "Track";
    public static final String EXTRA_EXTENSIONS = "Extensions";
    public static final String EXTRA_FROM = "From";
    public static final String EXTRA_TO = "To";

    // Constants
    private static final int LOCATION_MIN_TIME = 1000; // milliseconds
    private static final int LOCATION_MIN_DISTANCE = 1; // meters

    private static final int LOCATION_TRACKPOINT = 1;
    private static final int LOCATION_WAYPOINT = 2;
    private static final int LOCATION_PERIODIC = 3;

    private static double EGM96_INTERVAL = 15d / 60d; // 15' angle delta
    private static int EGM96_ROWS = 721;
    private static int EGM96_COLS = 1440;

    public LocationService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.w(TAG, "Intent=" + intent);

        if (ACTION_ACTIVITY.equals(intent.getAction()))
            handleActivity(intent);

        else if (ACTION_TRACKPOINT.equals(intent.getAction()) ||
                ACTION_WAYPOINT.equals(intent.getAction()) ||
                ACTION_ALARM.equals(intent.getAction()))
            handleLocationRequest(intent);

        else if (ACTION_LOCATION_FINE.equals(intent.getAction()) ||
                ACTION_LOCATION_COARSE.equals(intent.getAction()))
            handleLocationUpdate(intent);

        else if (ACTION_TIMEOUT.equals(intent.getAction()))
            handleLocationTimeout(intent);

        else if (ACTION_GEOTAGGED.equals(intent.getAction()))
            handleGeotagged(intent);

        else if (ACTION_SHARE.equals(intent.getAction()))
            handleShare(intent);

        else if (ACTION_UPLOAD.equals(intent.getAction()))
            handleUpload(intent);
    }

    // Handle intents methods

    private void handleActivity(Intent intent) {
        // Get last activity
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean lastStill = (prefs.getInt(ActivitySettings.PREF_LAST_ACTIVITY, DetectedActivity.UNKNOWN) == DetectedActivity.STILL);

        // Get detected activity
        ActivityRecognitionResult activityResult = ActivityRecognitionResult.extractResult(intent);
        DetectedActivity activity = activityResult.getMostProbableActivity();

        Log.w(TAG, "Activity=" + activity);
        if (activity.getConfidence() >= 50 && activity.getType() != DetectedActivity.TILTING) {
            // Persist probable activity
            prefs.edit().putInt(ActivitySettings.PREF_LAST_ACTIVITY, activity.getType()).apply();

            // Update still/moving
            if (!prefs.getBoolean(ActivitySettings.PREF_ACTIVE, false))
                showIdle(this);

            // Stop/start repeating alarm
            boolean still = (prefs.getInt(ActivitySettings.PREF_LAST_ACTIVITY, DetectedActivity.UNKNOWN) == DetectedActivity.STILL);
            if (lastStill != still)
                if (still) {
                    // Continue activity recognition
                    stopRepeatingAlarm(this);
                    stopLocating(this);
                } else
                    startRepeatingAlarm(this);
        }
    }

    private void handleLocationRequest(Intent intent) {
        // Guarantee fresh location
        if (ACTION_TRACKPOINT.equals(intent.getAction()) || ACTION_WAYPOINT.equals((intent.getAction())))
            stopLocating(this);

        // Persist location type
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (ACTION_TRACKPOINT.equals(intent.getAction()))
            prefs.edit().putInt(ActivitySettings.PREF_LOCATION_TYPE, LOCATION_TRACKPOINT).apply();
        else if (ACTION_WAYPOINT.equals((intent.getAction())))
            prefs.edit().putInt(ActivitySettings.PREF_LOCATION_TYPE, LOCATION_WAYPOINT).apply();
        else if (ACTION_ALARM.equals(intent.getAction()))
            prefs.edit().putInt(ActivitySettings.PREF_LOCATION_TYPE, LOCATION_PERIODIC).apply();

        // Try to acquire a new location
        startLocating(this);
    }

    private void handleLocationUpdate(Intent intent) {
        // Process location update
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int locationType = prefs.getInt(ActivitySettings.PREF_LOCATION_TYPE, -1);
        Location location = (Location) intent.getExtras().get(LocationManager.KEY_LOCATION_CHANGED);
        Log.w(TAG, "Update location=" + location + " type=" + locationType);
        if (location == null || (location.getLatitude() == 0.0 && location.getLongitude() == 0.0))
            return;

        // Correct altitude
        try {
            double offset = getEGM96Offset(location, this);
            Log.w(TAG, "Offset=" + offset);
            location.setAltitude(location.getAltitude() - offset);
            Log.w(TAG, "Corrected location=" + location + " type=" + locationType);
        } catch (IOException ex) {
            Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }

        // Get location preferences
        boolean pref_altitude = prefs.getBoolean(ActivitySettings.PREF_ALTITUDE, ActivitySettings.DEFAULT_ALTITUDE);
        float pref_accuracy = Float.parseFloat(prefs.getString(ActivitySettings.PREF_ACCURACY, ActivitySettings.DEFAULT_ACCURACY));
        float pref_inaccurate = Float.parseFloat(prefs.getString(ActivitySettings.PREF_INACCURATE, ActivitySettings.DEFAULT_INACCURATE));
        Log.w(TAG, "Prefer altitude=" + pref_altitude + " accuracy=" + pref_accuracy + " inaccurate=" + pref_inaccurate);

        if (!location.hasAccuracy() || location.getAccuracy() > pref_inaccurate) {
            Log.w(TAG, "Filtering inaccurate location=" + location);
            return;
        }

        // Persist better location
        Location bestLocation = LocationDeserializer.deserialize(prefs.getString(ActivitySettings.PREF_BEST_LOCATION, null));
        if (isBetterLocation(bestLocation, location)) {
            Log.w(TAG, "Better location=" + location);
            showNotification(getString(R.string.msg_acquired,
                            location.hasAccuracy() ? Math.round(location.getAccuracy()) : 0,
                            location.hasAltitude() ? Math.round(location.getAltitude()) : 0),
                    this);
            prefs.edit().putString(ActivitySettings.PREF_BEST_LOCATION, LocationSerializer.serialize(location)).apply();
        }

        // Check altitude
        if (!location.hasAltitude() && pref_altitude) {
            Log.w(TAG, "No altitude, but preferred, location=" + location);
            return;
        }

        // Check accuracy
        if (!location.hasAccuracy() || location.getAccuracy() > pref_accuracy) {
            Log.w(TAG, "Accuracy not reached, location=" + location);
            return;
        }

        stopLocating(this);

        // Process location
        handleLocation(locationType, location);
    }

    private void handleLocationTimeout(Intent intent) {
        // Process location time-out
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int locationType = prefs.getInt(ActivitySettings.PREF_LOCATION_TYPE, -1);
        Location bestLocation = LocationDeserializer.deserialize(prefs.getString(ActivitySettings.PREF_BEST_LOCATION, null));
        Log.w(TAG, "Timeout best location=" + bestLocation + " type=" + locationType);

        stopLocating(this);

        // Process location
        if (bestLocation != null)
            handleLocation(locationType, bestLocation);
    }

    private void handleGeotagged(Intent intent) {
        // Process photo location
        Location location = (Location) intent.getExtras().get(LocationManager.KEY_LOCATION_CHANGED);
        if (location != null)
            handleLocation(LOCATION_TRACKPOINT, location);
    }

    private void handleShare(Intent intent) {
        try {
            // Write GPX file
            String trackName = intent.getStringExtra(EXTRA_TRACK);
            boolean extensions = intent.getBooleanExtra(EXTRA_EXTENSIONS, false);
            long from = intent.getLongExtra(EXTRA_FROM, 0);
            long to = intent.getLongExtra(EXTRA_TO, 0);
            String gpxFileName = writeGPXFile(trackName, extensions, from, to, this);

            // View file
            Intent viewIntent = new Intent();
            viewIntent.setAction(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(Uri.fromFile(new File(gpxFileName)), "application/gpx+xml");
            viewIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(viewIntent);

            // Persist last share time
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putString(ActivitySettings.PREF_LAST_SHARE, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).apply();
        } catch (Throwable ex) {
            Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            toast(ex.toString(), this);
        }
    }

    private void handleUpload(Intent intent) {
        try {
            // Write GPX file
            String trackName = intent.getStringExtra(EXTRA_TRACK);
            boolean extensions = intent.getBooleanExtra(EXTRA_EXTENSIONS, false);
            long from = intent.getLongExtra(EXTRA_FROM, 0);
            long to = intent.getLongExtra(EXTRA_TO, 0);
            String gpxFileName = writeGPXFile(trackName, extensions, from, to, this);

            // Get GPX file content
            File gpx = new File(gpxFileName);
            byte[] bytes = new byte[(int) gpx.length()];
            DataInputStream in = new DataInputStream(new FileInputStream(gpx));
            in.readFully(bytes);
            in.close();

            // Get parameters
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            String blogUrl = prefs.getString(ActivitySettings.PREF_BLOGURL, "");
            int blogId = Integer.parseInt(prefs.getString(ActivitySettings.PREF_BLOGID, "1"));
            String userName = prefs.getString(ActivitySettings.PREF_BLOGUSER, "");
            String passWord = prefs.getString(ActivitySettings.PREF_BLOGPWD, "");

            // Create XML-RPC client
            XMLRPCClient client = new XMLRPCClient(new URL(blogUrl + "xmlrpc.php"));

            // Create upload parameters
            Map<String, Object> args = new HashMap<>();
            args.put("name", trackName + ".gpx");
            args.put("type", "text/xml");
            args.put("bits", bytes);
            args.put("overwrite", true);
            Object[] params = {blogId, userName, passWord, args};

            // Call
            HashMap<Object, Object> result = (HashMap<Object, Object>) client.call("bpt.upload", params);
            String url = result.get("url").toString();
            Log.w(TAG, "Uploaded url=" + url);

            // Persist last upload time
            prefs.edit().putString(ActivitySettings.PREF_LAST_UPLOAD, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).apply();

            // Feedback
            toast(getString(R.string.msg_uploaded, url), this);
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            vibrator.vibrate(500);
        } catch (Throwable ex) {
            Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            toast(ex.toString(), this);
        }
    }

    // Start/stop methods

    public static void startTracking(final Context context) {
        Log.w(TAG, "Start tracking");

        // Check if enabled
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(ActivitySettings.PREF_ENABLED, ActivitySettings.DEFAULT_ENABLED)) {
            Log.w(TAG, "Tracking disabled");
            return;
        }

        // Conditionally start repeating alarm
        boolean recognition = prefs.getBoolean(ActivitySettings.PREF_RECOGNITION_ENABLED, ActivitySettings.DEFAULT_RECOGNITION_ENABLED);
        boolean still = (prefs.getInt(ActivitySettings.PREF_LAST_ACTIVITY, DetectedActivity.UNKNOWN) == DetectedActivity.STILL);
        if (!recognition || !still)
            startRepeatingAlarm(context);

        showIdle(context);

        // Request activity updates
        if (recognition)
            new Thread(new Runnable() {
                @Override
                public void run() {
                    GoogleApiClient gac = new GoogleApiClient.Builder(context).addApi(ActivityRecognition.API).build();
                    gac.blockingConnect();
                    Log.w(TAG, "GoogleApiClient connected");
                    Intent activityIntent = new Intent(context, LocationService.class);
                    activityIntent.setAction(LocationService.ACTION_ACTIVITY);
                    PendingIntent pi = PendingIntent.getService(context, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                    int interval = Integer.parseInt(prefs.getString(ActivitySettings.PREF_RECOGNITION_INTERVAL, ActivitySettings.DEFAULT_RECOGNITION_INTERVAL));
                    ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(gac, interval * 60 * 1000, pi);
                    Log.w(TAG, "Activity updates frequency=" + interval + "m");
                }
            }).start();
    }

    public static void stopTracking(final Context context) {
        Log.w(TAG, "Stop tracking");

        stopRepeatingAlarm(context);
        stopLocating(context);
        cancelNotification(context);

        // Cancel activity updates
        new Thread(new Runnable() {
            @Override
            public void run() {
                GoogleApiClient gac = new GoogleApiClient.Builder(context).addApi(ActivityRecognition.API).build();
                gac.blockingConnect();
                Log.w(TAG, "GoogleApiClient connected");
                Intent activityIntent = new Intent(context, LocationService.class);
                activityIntent.setAction(LocationService.ACTION_ACTIVITY);
                PendingIntent pi = PendingIntent.getService(context, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                ActivityRecognition.ActivityRecognitionApi.removeActivityUpdates(gac, pi);
                Log.w(TAG, "Canceled activity updates");
            }
        }).start();
    }

    private static void startRepeatingAlarm(Context context) {
        // Set repeating alarm
        Intent alarmIntent = new Intent(context, LocationService.class);
        alarmIntent.setAction(LocationService.ACTION_ALARM);
        PendingIntent pi = PendingIntent.getService(context, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int frequency = Integer.parseInt(prefs.getString(ActivitySettings.PREF_FREQUENCY, ActivitySettings.DEFAULT_FREQUENCY));
        am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, frequency * 60 * 1000, pi);
        Log.w(TAG, "Start repeating alarm frequency=" + frequency + "m");
    }

    private static void stopRepeatingAlarm(Context context) {
        // Cancel repeating alarm
        Intent alarmIntent = new Intent(context, LocationService.class);
        alarmIntent.setAction(LocationService.ACTION_ALARM);
        PendingIntent pi = PendingIntent.getService(context, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi);
        Log.w(TAG, "Stop repeating alarm");
    }

    private static void startLocating(Context context) {
        Log.w(TAG, "Start locating");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

        // Mark active
        if (prefs.getBoolean(ActivitySettings.PREF_ACTIVE, false)) {
            Log.w(TAG, "Already active");
            return;
        }
        prefs.edit().putBoolean(ActivitySettings.PREF_ACTIVE, true).apply();

        // Request coarse location
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            Intent locationIntent = new Intent(context, LocationService.class);
            locationIntent.setAction(LocationService.ACTION_LOCATION_COARSE);
            PendingIntent pi = PendingIntent.getService(context, 0, locationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_MIN_TIME, LOCATION_MIN_DISTANCE, pi);
            Log.w(TAG, "Requested network locations");
        }

        // Request fine location
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Intent locationIntent = new Intent(context, LocationService.class);
            locationIntent.setAction(LocationService.ACTION_LOCATION_FINE);
            PendingIntent pi = PendingIntent.getService(context, 0, locationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_MIN_TIME, LOCATION_MIN_DISTANCE, pi);
            Log.w(TAG, "Requested GPS locations");
        }

        // Set location timeout
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            int timeout = Integer.parseInt(prefs.getString(ActivitySettings.PREF_TIMEOUT, ActivitySettings.DEFAULT_TIMEOUT));
            Intent alarmIntent = new Intent(context, LocationService.class);
            alarmIntent.setAction(LocationService.ACTION_TIMEOUT);
            PendingIntent pi = PendingIntent.getService(context, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + timeout * 1000, pi);
            Log.w(TAG, "Set timeout=" + timeout + "s");

            showNotification(context.getString(R.string.msg_acquiring), context);
        } else
            Log.w(TAG, "No location providers");
    }

    private static void stopLocating(Context context) {
        Log.w(TAG, "Stop locating");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

        // Cancel coarse location updates
        {
            Intent locationIntent = new Intent(context, LocationService.class);
            locationIntent.setAction(LocationService.ACTION_LOCATION_COARSE);
            PendingIntent pi = PendingIntent.getService(context, 0, locationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            lm.removeUpdates(pi);
        }

        // Cancel fine location updates
        {
            Intent locationIntent = new Intent(context, LocationService.class);
            locationIntent.setAction(LocationService.ACTION_LOCATION_FINE);
            PendingIntent pi = PendingIntent.getService(context, 0, locationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            lm.removeUpdates(pi);
        }

        // Cancel alarm
        {
            Intent alarmIntent = new Intent(context, LocationService.class);
            alarmIntent.setAction(LocationService.ACTION_TIMEOUT);
            PendingIntent pi = PendingIntent.getService(context, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            am.cancel(pi);
        }

        prefs.edit().remove(ActivitySettings.PREF_ACTIVE).apply();
        prefs.edit().remove(ActivitySettings.PREF_LOCATION_TYPE).apply();
        prefs.edit().remove(ActivitySettings.PREF_BEST_LOCATION).apply();
    }

    // Helper methods

    private boolean isBetterLocation(Location prev, Location current) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean pref_altitude = prefs.getBoolean(ActivitySettings.PREF_ALTITUDE, ActivitySettings.DEFAULT_ALTITUDE);
        return (prev == null ||
                ((!pref_altitude || !prev.hasAltitude() || current.hasAltitude()) &&
                        (current.hasAccuracy() ? current.getAccuracy() : Float.MAX_VALUE) <
                                (prev.hasAccuracy() ? prev.getAccuracy() : Float.MAX_VALUE)));
    }

    private void handleLocation(int locationType, Location location) {
        // Filter close locations
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        float pref_nearby = Float.parseFloat(prefs.getString(ActivitySettings.PREF_NEARBY, ActivitySettings.DEFAULT_NEARBY));
        Location lastLocation = LocationDeserializer.deserialize(prefs.getString(ActivitySettings.PREF_LAST_LOCATION, null));
        if (locationType == LOCATION_TRACKPOINT || locationType == LOCATION_WAYPOINT ||
                lastLocation == null || lastLocation.distanceTo(location) >= pref_nearby) {
            // New location
            Log.w(TAG, "New location=" + location + " type=" + locationType);

            // Get waypoint name
            String waypointName = null;
            if (locationType == LOCATION_WAYPOINT) {
                List<String> listAddress = reverseGeocode(location, this);
                if (listAddress == null || listAddress.size() == 0)
                    waypointName = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                else
                    waypointName = TextUtils.join(", ", listAddress);
            }

            // Persist new location
            new DatabaseHelper(this).insert(location, waypointName);
            prefs.edit().putString(ActivitySettings.PREF_LAST_LOCATION, LocationSerializer.serialize(location)).apply();

            // Feedback
            if (locationType == LOCATION_WAYPOINT) {
                toast(waypointName, this);
                Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                vibrator.vibrate(500);
            }

            if (!prefs.getBoolean(ActivitySettings.PREF_ACTIVE, false))
                showIdle(this);
        } else
            Log.w(TAG, "Filtered location=" + location);
    }

    private static int getEGM96Integer(InputStream is, int row, int col) throws IOException {
        int k = row * EGM96_COLS + col;
        is.reset();
        is.skip(k * 2);
        return is.read() * 256 + is.read();
    }

    private static double getEGM96Offset(Location location, Context context) throws IOException {
        InputStream is = null;
        try {
            is = context.getAssets().open("WW15MGH.DAC");

            double lat = location.getLatitude();
            double lon = (location.getLongitude() >= 0 ? location.getLongitude() : location.getLongitude() + 360);

            int topRow = (int) ((90 - lat) / EGM96_INTERVAL);
            if (lat <= -90)
                topRow = EGM96_ROWS - 2;
            int bottomRow = topRow + 1;

            int leftCol = (int) (lon / EGM96_INTERVAL);
            int rightCol = leftCol + 1;
            if (lon >= 360 - EGM96_INTERVAL) {
                leftCol = EGM96_COLS - 1;
                rightCol = 0;
            }

            double latTop = 90 - topRow * EGM96_INTERVAL;
            double lonLeft = leftCol * EGM96_INTERVAL;

            double ul = getEGM96Integer(is, topRow, leftCol);
            double ll = getEGM96Integer(is, bottomRow, leftCol);
            double lr = getEGM96Integer(is, bottomRow, rightCol);
            double ur = getEGM96Integer(is, topRow, rightCol);

            double u = (lon - lonLeft) / EGM96_INTERVAL;
            double v = (latTop - lat) / EGM96_INTERVAL;

            double pll = (1.0 - u) * (1.0 - v);
            double plr = u * (1.0 - v);
            double pur = u * v;
            double pul = (1.0 - u) * v;

            double offset = pll * ll + plr * lr + pur * ur + pul * ul;
            return offset / 100d;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static List<String> reverseGeocode(Location location, Context context) {
        List<String> listline = new ArrayList<>();
        if (location != null && Geocoder.isPresent())
            try {
                Geocoder geocoder = new Geocoder(context);
                List<Address> listPlace = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (listPlace != null && listPlace.size() > 0) {
                    for (int l = 0; l < listPlace.get(0).getMaxAddressLineIndex(); l++)
                        listline.add(listPlace.get(0).getAddressLine(l));
                }
            } catch (IOException ignored) {
            }
        return listline;
    }

    private static void showIdle(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean still = (prefs.getInt(ActivitySettings.PREF_LAST_ACTIVITY, DetectedActivity.UNKNOWN) == DetectedActivity.STILL);
        Location lastLocation = LocationDeserializer.deserialize(prefs.getString(ActivitySettings.PREF_LAST_LOCATION, null));

        showNotification(context.getString(R.string.msg_idle,
                        context.getString(still ? R.string.msg_still : R.string.msg_moving, context),
                        (lastLocation == null ? "-" : new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(new Date(lastLocation.getTime()))),
                        (lastLocation == null || !lastLocation.hasAltitude() ? 0 : Math.round(lastLocation.getAltitude()))),
                context);
    }

    private static void showNotification(String text, Context context) {
        // Build intent
        Intent riSettings = new Intent(context, ActivitySettings.class);
        riSettings.setAction("android.intent.action.MAIN");
        riSettings.addCategory("android.intent.category.LAUNCHER");
        riSettings.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // Build pending intent
        PendingIntent piSettings = PendingIntent.getActivity(context, 1, riSettings, PendingIntent.FLAG_UPDATE_CURRENT);

        // Build result intent update
        Intent riTrackpoint = new Intent(context, LocationService.class);
        riTrackpoint.setAction(LocationService.ACTION_TRACKPOINT);

        // Build pending intent waypoint
        PendingIntent piTrackpoint = PendingIntent.getService(context, 2, riTrackpoint, PendingIntent.FLAG_UPDATE_CURRENT);

        // Build result intent waypoint
        Intent riWaypoint = new Intent(context, LocationService.class);
        riWaypoint.setAction(LocationService.ACTION_WAYPOINT);

        // Build pending intent waypoint
        PendingIntent piWaypoint = PendingIntent.getService(context, 3, riWaypoint, PendingIntent.FLAG_UPDATE_CURRENT);

        // Build notification
        Notification.Builder notificationBuilder = new Notification.Builder(context);
        notificationBuilder.setSmallIcon(R.mipmap.ic_launcher);
        notificationBuilder.setContentTitle(context.getString(R.string.app_name));
        notificationBuilder.setContentText(text);
        notificationBuilder.setContentIntent(piSettings);
        notificationBuilder.setWhen(System.currentTimeMillis());
        notificationBuilder.setAutoCancel(false);
        notificationBuilder.setOngoing(true);
        notificationBuilder.addAction(android.R.drawable.ic_menu_mylocation, context.getString(R.string.title_trackpoint),
                piTrackpoint);
        notificationBuilder.addAction(android.R.drawable.ic_menu_add, context.getString(R.string.title_waypoint),
                piWaypoint);
        Notification notification = notificationBuilder.build();

        NotificationManager nm = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        nm.notify(0, notification);
    }

    private static void cancelNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(0);
    }

    private static String writeGPXFile(String trackName, boolean extensions, long from, long to, Context context) throws IOException {
        Log.w(TAG, "Writing track=" + trackName + " from=" + from + "to=" + to);
        File folder = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + File.separatorChar + "BackPackTrackII");
        folder.mkdirs();
        String gpxFileName = folder.getAbsolutePath() + File.separatorChar + trackName + ".gpx";
        Log.w(TAG, "Writing file=" + gpxFileName);
        DatabaseHelper databaseHelper = new DatabaseHelper(context);
        Cursor trackPoints = databaseHelper.getList(from, to, true);
        Cursor wayPoints = databaseHelper.getList(from, to, false);
        GPXFileWriter.writeGpxFile(new File(gpxFileName), trackName, extensions, trackPoints, wayPoints);
        databaseHelper.close();
        return gpxFileName;
    }

    private static void toast(final String text, final Context context) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show();
            }
        });
    }

    // Serialization

    private static class LocationSerializer implements JsonSerializer<Location> {
        public JsonElement serialize(Location src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jObject = new JsonObject();

            jObject.addProperty("Provider", src.getProvider());
            jObject.addProperty("Time", src.getTime());
            jObject.addProperty("Latitude", src.getLatitude());
            jObject.addProperty("Longitude", src.getLongitude());

            if (src.hasAltitude())
                jObject.addProperty("Altitude", src.getAltitude());

            if (src.hasSpeed())
                jObject.addProperty("Speed", src.getSpeed());

            if (src.hasAccuracy())
                jObject.addProperty("Accuracy", src.getAccuracy());

            if (src.hasBearing())
                jObject.addProperty("Bearing", src.getBearing());

            return jObject;
        }

        public static String serialize(Location location) {
            GsonBuilder builder = new GsonBuilder();
            builder.registerTypeAdapter(Location.class, new LocationSerializer());
            Gson gson = builder.create();
            return gson.toJson(location);
        }
    }

    private static class LocationDeserializer implements JsonDeserializer<Location> {
        public Location deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            JsonObject jObject = (JsonObject) json;
            Location location = new Location(jObject.get("Provider").getAsString());

            location.setTime(jObject.get("Time").getAsLong());
            location.setLatitude(jObject.get("Latitude").getAsDouble());
            location.setLongitude(jObject.get("Longitude").getAsDouble());

            if (jObject.has("Altitude"))
                location.setAltitude(jObject.get("Altitude").getAsDouble());

            if (jObject.has("Speed"))
                location.setSpeed(jObject.get("Speed").getAsFloat());

            if (jObject.has("Bearing"))
                location.setBearing(jObject.get("Bearing").getAsFloat());

            if (jObject.has("Accuracy"))
                location.setAccuracy(jObject.get("Accuracy").getAsFloat());

            return location;
        }

        public static Location deserialize(String json) {
            GsonBuilder builder = new GsonBuilder();
            builder.registerTypeAdapter(Location.class, new LocationDeserializer());
            Gson gson = builder.create();
            return gson.fromJson(json, Location.class);
        }
    }
}

package porqueno.streetcleanpark;

import android.content.Context;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;

import com.firebase.geofire.GeoFire;
import com.firebase.geofire.GeoLocation;
import com.firebase.geofire.GeoQuery;
import com.firebase.geofire.GeoQueryEventListener;
import com.google.android.gms.maps.model.LatLng;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.maps.android.geojson.GeoJsonFeature;
import com.google.maps.android.geojson.GeoJsonGeometry;
import com.google.maps.android.geojson.GeoJsonLayer;
import com.google.maps.android.geojson.GeoJsonLineString;
import com.google.maps.android.geojson.GeoJsonLineStringStyle;
import com.google.maps.android.geojson.GeoJsonPointStyle;
import com.google.maps.android.geojson.GeoJsonPolygonStyle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import porqueno.streetcleanpark.serialize.GeoJsonFeatureInstanceCreator;
import porqueno.streetcleanpark.serialize.GeoJsonGeometryDeserializer;
import porqueno.streetcleanpark.serialize.GeoJsonLineStringStyleSerializer;
import porqueno.streetcleanpark.serialize.GeoJsonPointStyleSerializer;
import porqueno.streetcleanpark.serialize.GeoJsonPolygonStyleSerializer;

/**
 * Created by jacob on 7/26/16.
 * Firebase DB model
 */
class FeatureModel implements GeoQueryEventListener, ValueEventListener, GeoFire.CompletionListener {
	private static final String TAG = "FeatureModel";
	private static final String CLEANING_DATA_REF = "cleaning";
	private static final String GEOFIRE_DATA_REF = "geofire";
	private static final int NUM_TO_BATCH_DESERIALIZE = 10;

	private FirebaseDatabase mDatabase;
	private GeoFire mGeoFire;
	private GeoQuery mGeoQuery;
	private AtomicInteger outstandingRequests;
	private AtomicInteger outstandingDeserializeBatches;
	private FeatureModelInterface mInterface;
	private Gson mGson;
	private Queue<String> deserializeQueue;


	FeatureModel(Context ctx) {
		mDatabase = FirebaseDatabase.getInstance();
		mGeoFire = new GeoFire(mDatabase.getReference(GEOFIRE_DATA_REF));
		mInterface = (FeatureModelInterface) ctx;
		outstandingRequests = new AtomicInteger(0);
		outstandingDeserializeBatches = new AtomicInteger(0);
		mGson = createGsonParser();
		deserializeQueue = new ArrayDeque<>();
	}

	public static Gson createGsonParser(){
		GsonBuilder mBuilder = new GsonBuilder();
		mBuilder.registerTypeAdapter(GeoJsonFeature.class, new GeoJsonFeatureInstanceCreator());
		mBuilder.registerTypeAdapter(GeoJsonGeometry.class, new GeoJsonGeometryDeserializer());
		return mBuilder.create();
	}

	private void importAllData(GeoJsonLayer layer) {
		DatabaseReference featureRef;
		for (GeoJsonFeature feature : layer.getFeatures()) {
			String json = serialize(feature);
			String key = getUniqueKey(feature);
			featureRef = mDatabase.getReference(getCleaningDataKey(key));
			featureRef.setValue(json);
		}
	}

	private void setAllGeos(GeoJsonLayer layer) {
		for (GeoJsonFeature feature : layer.getFeatures()) {
			GeoJsonLineString geo = (GeoJsonLineString) feature.getGeometry();
			List<LatLng> points = geo.getCoordinates();
			for (LatLng point: points) {
				mGeoFire.setLocation(
						getUniqueKey(feature), new GeoLocation(point.latitude, point.longitude),
						this);
			}
		}
	}

	private static String getCleaningDataKey(String key){
		return CLEANING_DATA_REF + "/" + key;
	}

	public void getFeaturesForPoint(double lat, double lng) {
		outstandingRequests.set(0);
		outstandingDeserializeBatches.set(0);
		if (mGeoQuery == null) {
			setupGeoQuery(lat, lng);
		} else {
			mGeoQuery.setCenter(new GeoLocation(lat, lng));
		}
	}

	private void setupGeoQuery(double lat, double lng) {
		mGeoQuery = mGeoFire.queryAtLocation(new GeoLocation(lat, lng), 0.5);
		mGeoQuery.addGeoQueryEventListener(this);
	}

	private void addValueListener(String key){
		outstandingRequests.incrementAndGet();
		mDatabase.getReference(getCleaningDataKey(key)).addValueEventListener(this);
	}

	private void removeValueListener(String key) {
		mDatabase.getReference(getCleaningDataKey(key)).removeEventListener(this);
		mInterface.featureLeft(key);
	}

	@Override
	public void onKeyEntered(String key, GeoLocation location) {
		addValueListener(key);
	}

	@Override
	public void onKeyExited(String key) {
		removeValueListener(key);
	}

	@Override
	public void onKeyMoved(String key, GeoLocation location) {}

	@Override
	public void onGeoQueryReady() {
		if (outstandingRequests.get() == 0){
			// When zooming on map we won't get new points/onDataChange event
			mInterface.doneFetching();
		}
	}

	@Override
	public void onGeoQueryError(DatabaseError error) {
		System.err.println("There was an error with this query: " + error);
	}

	@Override
	public void onDataChange(DataSnapshot dataSnapshot) {
		outstandingRequests.decrementAndGet();
		deserializeQueue.add(dataSnapshot.getValue(String.class));
		if (deserializeQueue.size() >= NUM_TO_BATCH_DESERIALIZE || outstandingRequests.get() <= 0){
			outstandingDeserializeBatches.incrementAndGet();
			new DeserializeAsyncTask().execute(deserializeQueue.toArray(new String[0]));
			deserializeQueue.clear();
		}
	}

	@Override
	public void onCancelled(DatabaseError databaseError) {
		outstandingRequests.decrementAndGet();
		Log.w(TAG, "Failed to read value.", databaseError.toException());
	}

	@Override
	public void onComplete(String databaseError, DatabaseError error) {
		if (error != null) {
			System.err.println("There was an error saving the location to GeoFire: " + error);
		}
	}

	//////////////////////// Utility methods

	public static int getWeekday(GeoJsonFeature feature) {
		String weekday =  feature.getProperty("WEEKDAY");
		switch (weekday){
			case "Mon":
				return Calendar.MONDAY;
			case "Tues":
				return Calendar.TUESDAY;
			case "Wed":
				return Calendar.WEDNESDAY;
			case "Thu":
				return Calendar.THURSDAY;
			case "Fri":
				return Calendar.FRIDAY;
			case "Sat":
				return Calendar.SATURDAY;
			case "Sun":
				return Calendar.SUNDAY;
			default:
				return -1;
		}
	}

	public static int getStartHour(GeoJsonFeature feature) {
		return getHour(feature.getProperty("FROMHOUR"));
	}

	public static int getEndHour(GeoJsonFeature feature) {
		return getHour(feature.getProperty("TOHOUR"));
	}

	public static int getStartMin(GeoJsonFeature feature) {
		return getMin(feature.getProperty("FROMHOUR"));
	}

	public static int getEndMin(GeoJsonFeature feature) {
		return getMin(feature.getProperty("TOHOUR"));
	}

	public static boolean getWeekOne(GeoJsonFeature feature) {
		return feature.getProperty("WEEK1OFMON").equals("Y");
	}

	public static boolean getWeekTwo(GeoJsonFeature feature) {
		return feature.getProperty("WEEK2OFMON").equals("Y");
	}

	public static boolean getWeekThree(GeoJsonFeature feature) {
		return feature.getProperty("WEEK3OFMON").equals("Y");
	}

	public static boolean getWeekFour(GeoJsonFeature feature) {
		return feature.getProperty("WEEK4OFMON").equals("Y");
	}

	public static boolean anyWeeksOff(GeoJsonFeature feature){
		return !(getWeekOne(feature) && getWeekTwo(feature) && getWeekThree(feature) && getWeekFour(feature));
	}

	@SuppressWarnings("WeakerAccess")
	public static int getHour(String time) {
		return Integer.parseInt(time.substring(0, 2));
	}

	@SuppressWarnings("WeakerAccess")
	public static int getMin(String time) {
		return Integer.parseInt(time.substring(3, 5));
	}

	public static String getUniqueKey(GeoJsonFeature feature){
		return feature.getProperty("BLOCKSWEEP");
	}

	public static String getUniqueKeyForBlockSide(GeoJsonFeature feature) {
		return feature.getProperty("CNN") + "_" + feature.getProperty("BLOCKSIDE");
	}

	public static Map<String,List<GeoJsonFeature>> clusterFeaturesByDay(List<GeoJsonFeature> features) {
		Map<String, List<GeoJsonFeature>> clustered = new HashMap<>();
		for (GeoJsonFeature feature : features) {
			String day = feature.getProperty("WEEKDAY");
			List<GeoJsonFeature> existing = clustered.get(day);
			if (existing == null){
				existing = new ArrayList<>();
			}
			existing.add(feature);
			clustered.put(day, existing);
		}
		return clustered;
	}

	public static Map<String,List<GeoJsonFeature>> clusterFeaturesByTime(List<GeoJsonFeature> features) {
		Map<String, List<GeoJsonFeature>> clustered = new HashMap<>();
		for (GeoJsonFeature feature : features) {
			String time = feature.getProperty("FROMHOUR") + "-" + feature.getProperty("TOHOUR");
			List<GeoJsonFeature> existing = clustered.get(time);
			if (existing == null){
				existing = new ArrayList<>();
			}
			existing.add(feature);
			clustered.put(time, existing);
		}
		return clustered;
	}

	public static String getDays(List<GeoJsonFeature> features){
		Set<String> days= new HashSet<>();
		for (GeoJsonFeature feature: features){
			days.add(feature.getProperty("WEEKDAY"));
		}
		if (days.size() == 5 && !(days.contains("Sun") || days.contains("Sat"))){
			return "Weekdays";
		} else {
			return TextUtils.join(", ", days.toArray());
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static String serialize(GeoJsonFeature feature) {
		GsonBuilder builder = new GsonBuilder();
		builder.registerTypeAdapter(GeoJsonLineStringStyle.class, new GeoJsonLineStringStyleSerializer());
		builder.registerTypeAdapter(GeoJsonPointStyle.class, new GeoJsonPointStyleSerializer());
		builder.registerTypeAdapter(GeoJsonPolygonStyle.class, new GeoJsonPolygonStyleSerializer());
		Gson gson = builder.create();
		return gson.toJson(feature);
	}

	private class DeserializeAsyncTask extends AsyncTask<String, GeoJsonFeature, Void> {
		protected Void doInBackground(String... jsons) {
			for (String json : jsons){
				publishProgress(deserialize(mGson, json));
			}
			return null;
		}

		@Override
		protected void onProgressUpdate(GeoJsonFeature... features) {
			for (GeoJsonFeature feature : features){
				mInterface.featureFound(feature);
			}
		}

		protected void onPostExecute(Void param) {
			if (outstandingDeserializeBatches.decrementAndGet() == 0 && deserializeQueue.size() == 0){
				// We have all data, we can now iterate to calc colors
				mInterface.doneFetching();
			}
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static GeoJsonFeature deserialize(Gson gson, String json) {
		return gson.fromJson(json, GeoJsonFeature.class);
	}

	public void writeAllGeoData(GeoJsonLayer layer) {
		importAllData(layer);
		setAllGeos(layer);
	}
}

package gstavrinos.destinationalarm

import android.Manifest
import android.content.Context
import android.location.*
import android.os.Bundle
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import android.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import android.util.Log
import android.location.Criteria
import android.location.Location.distanceBetween
import android.provider.SyncStateContract
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.widget.Toast
import com.tbruyelle.rxpermissions2.RxPermissions
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.Marker
import android.widget.ImageButton



class MainActivity : AppCompatActivity() {

    private var map: MapView? = null
    private var locationManager: LocationManager? = null
    private var provider:String? = "tmp"
    private var gpsLocationListener:LocationListener? = null
    private var minDist:Float = 1000.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //handle permissions first, before map is created. not depicted here

        //load/initialize the osmdroid configuration, this can be done
        val ctx = applicationContext
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
        //setting this before the layout is inflated is a good idea
        //it 'should' ensure that the map has a writable location for the map cache, even without permissions
        //if no tiles are displayed, you can try overriding the cache path using Configuration.getInstance().setCachePath
        //see also StorageUtils
        //note, the load method also sets the HTTP User Agent to your application's package name, abusing osm's tile servers will get you banned based on this string

        //inflate and create the map
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.mapview)
        map!!.setTileSource(TileSourceFactory.MAPNIK)
        map!!.setBuiltInZoomControls(true)
        map!!.setMultiTouchControls(true)
        map!!.isTilesScaledToDpi = true
        map!!.isFlingEnabled = true
        map!!.minZoomLevel = 3.5

        Log.e(map!!.minZoomLevel.toString(), map!!.minZoomLevel.toString())
        val mapController = map!!.controller
        mapController.setZoom(5.0)
        val startPoint = GeoPoint(37.981912,23.727447)
        mapController.setCenter(startPoint)

        // SHOW USER'S LOCATION :)
        val mLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        mLocationOverlay.enableMyLocation()
        mLocationOverlay.enableFollowLocation()
        mLocationOverlay.isOptionsMenuEnabled = true
        map!!.overlays.add(mLocationOverlay)


        val targetMarker = Marker(map)

        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun longPressHelper(p: GeoPoint?): Boolean {
                targetMarker.position = p
                targetMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map!!.overlays.add(targetMarker)
                // TODO visualize min distance (minDist)
                map!!.invalidate()
                return true
            }

            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                Toast.makeText(applicationContext, "Tapped", Toast.LENGTH_SHORT).show()
                return true
            }

        })

        map!!.overlays.add(0, mapEventsOverlay)

        var btCenterMap = findViewById<ImageButton>(R.id.ic_center_map)

        btCenterMap.setOnClickListener {
            mLocationOverlay.enableFollowLocation()
        }

        val mContext = applicationContext
        locationManager = mContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGPSEnabled = locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)

        val rxPermissions = RxPermissions(this)

        rxPermissions
                .request(Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.INTERNET,
                        Manifest.permission.ACCESS_NETWORK_STATE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) // ask single or multiple permission once
                .subscribe { granted ->
                    if (granted) {
                        if (isGPSEnabled) {
                            if (locationManager != null) {
                                provider = locationManager!!.getBestProvider(Criteria(), false)

                                gpsLocationListener = object : LocationListener {
                                    override fun onLocationChanged(loc: Location) {
                                        // TODO here is where you check the user's location
                                        val results = FloatArray(3)
                                        distanceBetween(loc.latitude, loc.longitude, targetMarker.position.latitude, targetMarker.position.longitude, results)
                                        if (results[0] <= minDist){
                                            Toast.makeText(applicationContext, "WAKE UP SLEEPY CAT!", Toast.LENGTH_LONG).show()
                                        }


                                    }
                                    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
                                        Log.e("statusChanged!", "statusChanged!")
                                    }
                                    override fun onProviderEnabled(provider: String) {
                                        Log.e("STARTEDlocationListener", "STARTEDgpslocationListener!")
                                    }
                                    override fun onProviderDisabled(provider: String) {
                                        Log.e("STOPPEDProviderDisabled", "STOPPEDonProviderDisabled!")
                                    }
                                }
                                try {
                                    locationManager!!.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                                            1, 1f,
                                            gpsLocationListener)
                                }
                                catch (e: SecurityException) {
                                    // TODO handle this!
                                }
                            }
                        }
                    } else {
                        // At least one permission is denied
                    }
                }

    }

    public override fun onResume() {
        super.onResume()
        //this will refresh the osmdroid configuration on resuming.
        //if you make changes to the configuration, use
        //SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        map!!.onResume() //needed for compass, my location overlays, v6.0.0 and up
    }

    public override fun onPause() {
        super.onPause()
        //this will refresh the osmdroid configuration on resuming.
        //if you make changes to the configuration, use
        //SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //Configuration.getInstance().save(this, prefs);
        map!!.onPause()  //needed for compass, my location overlays, v6.0.0 and up
    }



}

package gstavrinos.destinationalarm

import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.support.v7.app.AppCompatActivity
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.SeekBar.OnSeekBarChangeListener
import com.tbruyelle.rxpermissions2.RxPermissions
import org.osmdroid.api.IGeoPoint
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.*


class MainActivity : AppCompatActivity() {

    private var map: MapView? = null
    private var locationManager: LocationManager? = null
    private var provider:String? = "tmp"
    private var gpsLocationListener:LocationListener? = null
    private var minDist:Int = 1000
    private var circle = NoTapPolygon(null)
    private var check:Boolean = false
    private val this_ = this
    private var settings:SharedPreferences? = null
    private var editor: SharedPreferences.Editor? = null
    private var favourites: MutableSet<String> = TreeSet()
    private val fav_locs = ArrayList<String>()
    private val fav_lats = ArrayList<Double>()
    private val fav_lons = ArrayList<Double>()
    private var targetMarker:Marker? = null
    private var mLocationOverlay:MyLocationNewOverlay? = null
    private var notification: Uri? = null
    private var ringtone: Ringtone? = null

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


        notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(applicationContext, notification)

        settings = getSharedPreferences("destinationAlarmU.P", Context.MODE_PRIVATE)
        editor = settings!!.edit()

        minDist = settings!!.getInt("minDist", 1000)

        favourites = settings!!.getStringSet("favourites", TreeSet())
        val arraylists = updateFavLocArrayLists(favourites)
        fav_locs.clear()
        fav_locs.addAll(arraylists.first)
        fav_lats.clear()
        fav_lats.addAll(arraylists.second)
        fav_lons.clear()
        fav_lons.addAll(arraylists.third)

        map = findViewById(R.id.mapview)
        map!!.setTileSource(TileSourceFactory.MAPNIK)
        map!!.setBuiltInZoomControls(true)
        map!!.setMultiTouchControls(true)
        map!!.isTilesScaledToDpi = true
        map!!.isFlingEnabled = true
        map!!.minZoomLevel = 3.5

        val settingsbutton:ImageButton = findViewById(R.id.settings_button)

        settingsbutton.setOnClickListener {
            showPopupSettings()
        }

        val favbutton:ImageButton = findViewById(R.id.favourites_button)

        favbutton.setOnClickListener {
            showPopupFav()
        }

        circle = NoTapPolygon(map)

        Log.e(map!!.minZoomLevel.toString(), map!!.minZoomLevel.toString())
        val mapController = map!!.controller
        mapController.setZoom(5.0)
        val startPoint = GeoPoint(37.981912,23.727447)
        mapController.setCenter(startPoint)

        // SHOW USER'S LOCATION :)
        mLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        mLocationOverlay!!.enableMyLocation()
        mLocationOverlay!!.enableFollowLocation()
        mLocationOverlay!!.isOptionsMenuEnabled = true
        map!!.overlays.add(mLocationOverlay)


        targetMarker = Marker(map)

        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun longPressHelper(p: GeoPoint?): Boolean {
                addTargetMarker(p!!.latitude, p!!.longitude)
                return true
            }

            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                Toast.makeText(applicationContext, "Tapped", Toast.LENGTH_SHORT).show()
                return true
            }

        })

        map!!.overlays.add(0, mapEventsOverlay)

        val btCenterMap = findViewById<ImageButton>(R.id.ic_center_map)

        btCenterMap.setOnClickListener {
            mLocationOverlay!!.enableFollowLocation()
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
                                        if(check) {
                                            val results = FloatArray(3)
                                            distanceBetween(loc.latitude, loc.longitude, targetMarker!!.position.latitude, targetMarker!!.position.longitude, results)
                                            if (results[0] <= minDist) {
                                                Toast.makeText(applicationContext, "WAKE UP SLEEPY CAT!", Toast.LENGTH_LONG).show()
                                                ringtone!!.play()
                                                map!!.overlays.remove(targetMarker)
                                                map!!.overlays.remove(circle)
                                                check = false
                                                val builder = AlertDialog.Builder(this_)
                                                builder.setTitle("WAKE UP!")
                                                .setMessage("Stop alarm?")
                                                .setPositiveButton(android.R.string.yes) { dialog, _ ->
                                                    ringtone!!.stop()
                                                    dialog.cancel()
                                                }.setIcon(android.R.drawable.ic_dialog_alert)
                                                .show()
                                            }
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

    public override fun onDestroy() {
        super.onDestroy()

    }

    private fun showPopupSettings() {

        val popupView:View  = layoutInflater.inflate(R.layout.settings, null)

        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        popupWindow.isFocusable = true

        val radiusValue = popupView.findViewById<TextView>(R.id.radius_value)

        val seekbar = popupView.findViewById<SeekBar>(R.id.radius_seekBar)
        seekbar.progress = minDist
        radiusValue.text = minDist.toString()

        seekbar.setOnSeekBarChangeListener(object:OnSeekBarChangeListener {

            override fun onStopTrackingTouch(seekBar:SeekBar) {
                minDist = seekBar.progress + 20
                editor!!.clear()
                editor!!.putInt("minDist", minDist)
                editor!!.commit()
            }

            override fun onStartTrackingTouch(seekBar:SeekBar) {}

            override fun onProgressChanged(seekBar:SeekBar, progress:Int,fromUser:Boolean) {
                radiusValue.text = (progress+20).toString()
            }
        })
        val bg = ColorDrawable(0x8033b5e5.toInt())
        popupWindow.setBackgroundDrawable(bg)
        popupWindow.showAtLocation(findViewById(android.R.id.content), Gravity.CENTER, 0, 0)

    }

    private fun showPopupFavSave() {

        val popupView:View  = layoutInflater.inflate(R.layout.favourites_save, null)

        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        popupWindow.isFocusable = true

        val savebutton = popupView.findViewById<Button>(R.id.save_button)
        val cancelbutton = popupView.findViewById<Button>(R.id.cancel_button)
        val favn = popupView.findViewById<EditText>(R.id.fav_name)

        savebutton.setOnClickListener{
            if (favn.text.contains("/")){
                Toast.makeText(this_, "Location name cannot contain a \"/\"!", Toast.LENGTH_LONG).show()
            }
            else if(favn.text.isEmpty()){
                Toast.makeText(this_, "Location name cannot be empty!", Toast.LENGTH_LONG).show()
            }
            else{
                val stringToAdd = locationStringGenerator(favn.text.toString(), targetMarker)
                if(fav_locs.contains(favn.text.toString())){
                    Toast.makeText(this_, "Already in favourites!", Toast.LENGTH_LONG).show()
                }
                else{
                    favourites.add(stringToAdd)
                    val arraylists = updateFavLocArrayLists(favourites)
                    fav_locs.clear()
                    fav_locs.addAll(arraylists.first)
                    fav_lats.clear()
                    fav_lats.addAll(arraylists.second)
                    fav_lons.clear()
                    fav_lons.addAll(arraylists.third)
                    editor!!.clear()
                    editor!!.putStringSet("favourites", favourites)
                    editor!!.commit()
                    Toast.makeText(this_, "Location added to favourites!", Toast.LENGTH_LONG).show()
                    popupWindow.dismiss()
                }

            }
        }

        cancelbutton.setOnClickListener{
            popupWindow.dismiss()
        }


        val bg = ColorDrawable(0x8033b5e5.toInt())
        popupWindow.setBackgroundDrawable(bg)
        popupWindow.showAtLocation(findViewById(android.R.id.content), Gravity.CENTER, 0, 0)

    }

    private fun showPopupFav() {
        if(!fav_locs.isEmpty()){
            val popupView: View = layoutInflater.inflate(R.layout.favourites_list, null)

            val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            popupWindow.isFocusable = true

            val favlist = popupView.findViewById<ListView>(R.id.fav_list)
            val adapter = ArrayAdapter<String>(this_,android.R.layout.simple_list_item_1, fav_locs)
            favlist.adapter = adapter

            favlist.setOnItemLongClickListener{ parent, _, position, _ ->
                val builder = AlertDialog.Builder(this_)
                builder.setTitle("WARNING!")
                        .setMessage("Remove location from favourites?")
                        .setPositiveButton(android.R.string.yes) { dialog, _ ->
                            favourites.remove(locationStringGenerator(fav_locs[position], fav_lats[position], fav_lons[position]))
                            val arraylists = updateFavLocArrayLists(favourites)
                            fav_locs.clear()
                            fav_locs.addAll(arraylists.first)
                            fav_lats.clear()
                            fav_lats.addAll(arraylists.second)
                            fav_lons.clear()
                            fav_lons.addAll(arraylists.third)
                            adapter.notifyDataSetChanged()
                            editor!!.clear()
                            editor!!.putStringSet("favourites", favourites)
                            editor!!.commit()
                            Toast.makeText(this_, "Location removed from favourites!", Toast.LENGTH_LONG).show()
                            dialog.cancel()
                        }
                        .setNegativeButton(android.R.string.no) { dialog, _ ->
                            dialog.cancel()
                        }
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show()
                true
            }

            favlist.setOnItemClickListener{ _, _, position, _ ->
                addTargetMarker(fav_lats[position], fav_lons[position])
                mLocationOverlay!!.disableFollowLocation()
                map!!.setExpectedCenter(GeoPoint(fav_lats[position], fav_lons[position]))
                popupWindow.dismiss()
                true
            }


            val bg = ColorDrawable(0x8033b5e5.toInt())
            popupWindow.setBackgroundDrawable(bg)
            popupWindow.showAtLocation(findViewById(android.R.id.content), Gravity.CENTER, 0, 0)
        }
        else{
            Toast.makeText(this_, "No favourite locations saved!", Toast.LENGTH_LONG).show()
        }
    }

    private fun addTargetMarker(lat:Double, lon:Double){
        val p = GeoPoint(lat, lon)
        map!!.overlays.remove(circle)
        targetMarker!!.position = p
        val oml = Marker.OnMarkerClickListener { _, _ ->
            showPopupFavSave()
            true
        }
        targetMarker!!.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        targetMarker!!.setOnMarkerClickListener(oml)
        circle.points = Polygon.pointsAsCircle(p, minDist.toDouble())
        circle.fillColor = 0x12121212
        circle.strokeColor = Color.RED
        circle.strokeWidth = 2.0f
        map!!.overlays.add(targetMarker)
        map!!.overlays.add(circle)
        map!!.invalidate()
        check = true
        Toast.makeText(applicationContext, "New alarm destination set!", Toast.LENGTH_SHORT).show()
    }

    private fun locationStringGenerator(s:String, targetMarker:Marker?) : String{
        var tmp = s + "/" + targetMarker!!.position.latitude.toString() + "/" + targetMarker!!.position.longitude.toString()
        return tmp
    }

    private fun locationStringGenerator(s:String, lat:Double, lon:Double) : String{
        var tmp = s + "/" + lat.toString() + "/" + lon.toString()
        return tmp
    }

    private fun updateFavLocArrayLists(favourites:MutableSet<String>) : Triple<ArrayList<String>, ArrayList<Double>, ArrayList<Double>>{
        val tmp = ArrayList<String>()
        val tmp2 = ArrayList<Double>()
        val tmp3 = ArrayList<Double>()
        for(f in favourites){
            tmp.add(f.split("/")[0])
            tmp2.add(f.split("/")[1].toDouble())
            tmp3.add(f.split("/")[2].toDouble())
        }
        return Triple(tmp, tmp2, tmp3)
    }

    class NoTapPolygon(map:MapView?) : Polygon(map) {

        override fun onSingleTapConfirmed(e: MotionEvent, mapView:MapView ): Boolean {
            return false
        }
    }
}

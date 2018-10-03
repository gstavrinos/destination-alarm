package gstavrinos.destinationalarm

import android.Manifest
import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.*
import android.os.Bundle
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import android.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import android.location.Location.distanceBetween
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.IBinder
import android.support.v4.app.NotificationCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.*
import android.widget.*
import android.widget.SeekBar.OnSeekBarChangeListener
import com.tbruyelle.rxpermissions2.RxPermissions
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.*


var map: MapView? = null
var locationManager: LocationManager? = null
var ringtone: Ringtone? = null
var targetMarker:Marker? = null
var minDist:Int = 1000
var circle = MainActivity.NoTapPolygon(null)
var check:Boolean = false
var gpsLocationListener:LocationListener? = null
var this_:MainActivity? = null

private var rxPermissions:RxPermissions? = null
class MainActivity : AppCompatActivity(){
    private var settings:SharedPreferences? = null
    private var editor: SharedPreferences.Editor? = null
    private var favourites: MutableSet<String> = TreeSet()
    private val favLocs = ArrayList<String>()
    private val favLats = ArrayList<Double>()
    private val favLons = ArrayList<Double>()
    private var mLocationOverlay:MyLocationNewOverlay? = null
    private var notification: Uri? = null
    private var mConnection: ServiceConnection? = null
    private var notif:Notification? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //handle permissions first, before map is created. not depicted here

        //load/initialize the osmdroid configuration, this can be done
        val ctx = applicationContext
        this_ = this
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
        //setting this before the layout is inflated is a good idea
        //it 'should' ensure that the map has a writable location for the map cache, even without permissions
        //if no tiles are displayed, you can try overriding the cache path using Configuration.getInstance().setCachePath
        //see also StorageUtils
        //note, the load method also sets the HTTP User Agent to your application's package name, abusing osm's tile servers will get you banned based on this string

        //inflate and create the map
        setContentView(R.layout.activity_main)

        val thisIntent = Intent(ctx, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(ctx, 0, thisIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        notif = NotificationCompat.Builder(this, "Destination Alarm")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Destination Alarm")
                .setContentText("Don't worry, the alarm is still active in the background!")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()

        notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(applicationContext, notification)

        settings = getSharedPreferences("destinationAlarmU.P", Context.MODE_PRIVATE)
        editor = settings!!.edit()

        minDist = settings!!.getInt("minDist", 1000)

        favourites = settings!!.getStringSet("favourites", TreeSet())
        val arraylists = updateFavLocArrayLists(favourites)
        favLocs.clear()
        favLocs.addAll(arraylists.first)
        favLats.clear()
        favLats.addAll(arraylists.second)
        favLons.clear()
        favLons.addAll(arraylists.third)

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

        rxPermissions = RxPermissions(this)
        mConnection = object : ServiceConnection {

            override fun onServiceConnected(className: ComponentName, service: IBinder) {
            }

            override fun onServiceDisconnected(className: ComponentName) {
            }
        }

        val intent = Intent(this, LocationService2::class.java)
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }

    public override fun onResume() {
        super.onResume()
        //this will refresh the osmdroid configuration on resuming.
        //if you make changes to the configuration, use
        //SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        //map!!.onResume() //needed for compass, my location overlays, v6.0.0 and up
    }

    public override fun onPause() {
        super.onPause()
        //this will refresh the osmdroid configuration on resuming.
        //if you make changes to the configuration, use
        //SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //Configuration.getInstance().save(this, prefs);
        //map!!.onPause()  //needed for compass, my location overlays, v6.0.0 and up
        //ringtone!!.stop()
        //TODO think about what to do when the app is running on the background
        val notificationManager:NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(0, notif)

        Log.e("asdasdasdas","aaaaaaaaa")
    }

    public override fun onDestroy() {
        super.onDestroy()
        unbindService(mConnection)
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
                if(favLocs.contains(favn.text.toString())){
                    Toast.makeText(this_, "Already in favourites!", Toast.LENGTH_LONG).show()
                }
                else{
                    favourites.add(stringToAdd)
                    val arraylists = updateFavLocArrayLists(favourites)
                    favLocs.clear()
                    favLocs.addAll(arraylists.first)
                    favLats.clear()
                    favLats.addAll(arraylists.second)
                    favLons.clear()
                    favLons.addAll(arraylists.third)
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
        if(!favLocs.isEmpty()){
            val popupView: View = layoutInflater.inflate(R.layout.favourites_list, null)

            val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            popupWindow.isFocusable = true

            val favlist = popupView.findViewById<ListView>(R.id.fav_list)
            val adapter = ArrayAdapter<String>(this_,android.R.layout.simple_list_item_1, favLocs)
            favlist.adapter = adapter

            favlist.setOnItemLongClickListener{ parent, _, position, _ ->
                val builder = AlertDialog.Builder(this_)
                builder.setTitle("WARNING!")
                        .setMessage("Remove location from favourites?")
                        .setPositiveButton(android.R.string.yes) { dialog, _ ->
                            favourites.remove(locationStringGenerator(favLocs[position], favLats[position], favLons[position]))
                            val arraylists = updateFavLocArrayLists(favourites)
                            favLocs.clear()
                            favLocs.addAll(arraylists.first)
                            favLats.clear()
                            favLats.addAll(arraylists.second)
                            favLons.clear()
                            favLons.addAll(arraylists.third)
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
                addTargetMarker(favLats[position], favLons[position])
                mLocationOverlay!!.disableFollowLocation()
                map!!.setExpectedCenter(GeoPoint(favLats[position], favLons[position]))
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

    class LocationService2 : Service() {

        override fun onBind(intent: Intent?): IBinder? {
            val mContext = applicationContext
            locationManager = mContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isGPSEnabled = locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)

            rxPermissions!!.request(Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) // ask single or multiple permission once
                    .subscribe { granted ->
                        if (granted) {
                            if (isGPSEnabled) {
                                if (locationManager != null) {

                                    gpsLocationListener = object : LocationListener {
                                        override fun onLocationChanged(loc: Location) {
                                            // TODO here is where you check the user's location
                                            if (check) {
                                                val results = FloatArray(3)
                                                distanceBetween(loc.latitude, loc.longitude, targetMarker!!.position.latitude, targetMarker!!.position.longitude, results)
                                                if (results[0] <= minDist) {
                                                    ringtone!!.play()
                                                    map!!.overlays.remove(circle)
                                                    map!!.overlays.remove(targetMarker)
                                                    check = false
                                                    val builder = AlertDialog.Builder(this_)
                                                    builder.setTitle("WAKE UP!")
                                                            .setMessage("Stop alarm?")
                                                            .setPositiveButton(android.R.string.yes) { dialog, _ ->
                                                                dialog.cancel()
                                                            }.setIcon(android.R.drawable.ic_dialog_alert)
                                                            .setOnCancelListener {
                                                                ringtone!!.stop()
                                                            }
                                                            .show()
                                                }
                                            }
                                        }
                                        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
                                        override fun onProviderEnabled(provider: String) {}
                                        override fun onProviderDisabled(provider: String) {}
                                    }
                                    try {
                                        locationManager!!.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                                                1, 1f,
                                                gpsLocationListener)
                                    } catch (e: SecurityException) {
                                        // TODO handle this!
                                    }
                                }
                            }
                        } else {
                            // At least one permission is denied
                        }
                    }
            return null
        }


    }
}

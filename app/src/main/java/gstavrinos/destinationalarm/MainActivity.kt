package gstavrinos.destinationalarm

import android.Manifest
import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.*
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import android.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import android.location.Location.distanceBetween
import android.media.*
import android.net.Uri
import android.os.*
import android.support.v4.app.NotificationCompat
import android.support.v7.app.AppCompatActivity
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.widget.*
import android.widget.SeekBar.OnSeekBarChangeListener
import com.tbruyelle.rxpermissions2.RxPermissions
import org.osmdroid.bonuspack.location.GeocoderNominatim
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import java.lang.Exception
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList


var map: MapView? = null
var locationManager: LocationManager? = null
var ringtone: Ringtone? = null
var targetMarker:Marker? = null
var minDist:Int = 1000
var circle = MainActivity.NoTapPolygon(null)
var check:Boolean = false
var gpsLocationListener:LocationListener? = null
var this_:MainActivity? = null
var notif:Notification? = null
var vib: Vibrator? = null

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
    private var audioManager: AudioManager? = null


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

        val pendingIntent: PendingIntent =
                Intent(this, MainActivity::class.java).let { notificationIntent ->
                    notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_CANCEL_CURRENT)
                }
        notif = NotificationCompat.Builder(this, createNotificationChannel())
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Destination Alarm")
                .setContentText("Don't worry, the alarm is still active in the background!")
                .setContentIntent(pendingIntent)
                .setTicker("Destination Alarm")
                .build()
        notif!!.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

        settings = getSharedPreferences("destinationAlarmU.P", Context.MODE_PRIVATE)
        editor = settings!!.edit()


        val snd = settings!!.getString("alarm_sound", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString())
        val alarmFile = File(snd)
        if (!alarmFile.exists()) {
            notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }
        else{
            notification = Uri.parse(snd)
        }

        ringtone = RingtoneManager.getRingtone(applicationContext, notification)
        ringtone!!.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED).build()

        this.volumeControlStream = AudioManager.STREAM_ALARM

        minDist = settings!!.getInt("minDist", 1000)


//        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
//        audioManager!!.mode = AudioManager.MODE_NORMAL
//        Log.e("asdasdasdasdasdddddddddddddd", audioManager!!.isBluetoothScoOn.toString())
//        audioManager!!.isBluetoothScoOn = false
//        audioManager!!.isSpeakerphoneOn = true
//
//        audioManager!!.isSpeakerphoneOn = settings!!.getBoolean("useSpeaker", true)

        vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

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

        val search_button:ImageButton = findViewById(R.id.search_button)

        search_button.setOnClickListener {
            showPopupAddressList()
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
        // map!!.onPause()  //needed for compass, my location overlays, v6.0.0 and up
        //ringtone!!.stop()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == -1 && requestCode == 5) {
            val tmp:Uri? = data!!.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (tmp != null) {
                notification = tmp
                editor!!.putString("alarm_sound", notification.toString())
                editor!!.commit()
                ringtone = RingtoneManager.getRingtone(applicationContext, notification)
                ringtone!!.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED).build()
                Toast.makeText(this_, "New alarm sound set!", Toast.LENGTH_SHORT).show()
            }
            else{
                Toast.makeText(this_, "The sound of the alarm cannot be set to none!", Toast.LENGTH_LONG).show()
            }
        }
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

        val alarm_sound_button = popupView.findViewById<Button>(R.id.alarm_sound_button)
        alarm_sound_button.setOnClickListener(object: View.OnClickListener{
            override fun onClick(v: View?) {
                val sndmngt_intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                sndmngt_intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                sndmngt_intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Alarm Sound")
                sndmngt_intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, notification)
                sndmngt_intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                startActivityForResult(sndmngt_intent, 5)
            }

        })

        val alarm_sound_selection: RadioGroup = popupView.findViewById(R.id.sound_source_selection)
        if(settings!!.getBoolean("useSpeaker", true)){
            alarm_sound_selection.check((R.id.speaker_radio))
        }
        else{
            alarm_sound_selection.check((R.id.headphones_radio))
        }

        alarm_sound_selection.setOnCheckedChangeListener(object: RadioGroup.OnCheckedChangeListener{
            override fun onCheckedChanged(group: RadioGroup?, checkedId: Int) {
                editor!!.putBoolean("useSpeaker", checkedId == R.id.speaker_radio)
                editor!!.commit()
                //audioManager!!.isSpeakerphoneOn = checkedId == R.id.speaker_radio
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

    private fun showPopupAddressList() {
        val popupView: View = layoutInflater.inflate(R.layout.address_list, null)

        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        popupWindow.isFocusable = true

        val addresses = ArrayList<Address>()
        val addressesString = ArrayList<String>()
        val addressList = popupView.findViewById<ListView>(R.id.address_list)
        val adapter = ArrayAdapter<String>(this_,android.R.layout.simple_list_item_1, addressesString)
        addressList.adapter = adapter

        val search_button = popupView.findViewById<ImageButton>(R.id.search_button)
        search_button.isEnabled = false

        val address_search = popupView.findViewById<EditText>(R.id.address_search)

        address_search.addTextChangedListener(object: TextWatcher{
            override fun afterTextChanged(s: Editable?) {}

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                search_button.isEnabled = !s!!.trim().isEmpty()
            }

        })

        search_button.setOnClickListener(object: View.OnClickListener{
            override fun onClick(v: View?) {
                Log.e("dkhskdfsdfsdf",search_button.isEnabled.toString())
                search_button.isEnabled = false
                try {
                    val searchAddressTask = ReverseGeocodingAsyncTask(address_search.text.toString(), 50)
                    addresses.clear()
                    addressesString.clear()
                    adapter.notifyDataSetChanged()
                    searchAddressTask.execute()
                    addresses.addAll(searchAddressTask.get(10, TimeUnit.SECONDS))
                }
                catch(e:Exception){
                    Toast.makeText(this_, "Connection problem! Please check your internet access!", Toast.LENGTH_LONG).show()
                }

                if (addresses.isEmpty()){
                    Toast.makeText(this_, "No such location found!", Toast.LENGTH_LONG).show()
                }
                else{
                    for(i in addresses){
                        var nextAddress = ""
                        for(j in 0..i.maxAddressLineIndex){
                            nextAddress += i.getAddressLine(j) + ", "
                        }
                        nextAddress = nextAddress.removeRange(nextAddress.length-2, nextAddress.length)
                        addressesString.add(nextAddress)
                        nextAddress = ""
                    }
                    adapter.notifyDataSetChanged()
                }
                search_button.isEnabled = true
            }

        })

        addressList.setOnItemClickListener{ _, _, position, _ ->
            addTargetMarker(addresses[position].latitude, addresses[position].longitude)
            mLocationOverlay!!.disableFollowLocation()
            map!!.setExpectedCenter(GeoPoint(addresses[position].latitude, addresses[position].longitude))
            popupWindow.dismiss()
            true
        }


        val bg = ColorDrawable(0x8033b5e5.toInt())
        popupWindow.setBackgroundDrawable(bg)
        popupWindow.showAtLocation(findViewById(android.R.id.content), Gravity.TOP, 0, 0)
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

    private fun isHeadphonesPlugged(): Boolean{
        val audioDevices: Array<AudioDeviceInfo> = audioManager!!.getDevices(AudioManager.GET_DEVICES_ALL)
        for(deviceInfo in audioDevices){
            if(deviceInfo.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || deviceInfo.type == AudioDeviceInfo.TYPE_WIRED_HEADSET){
                return true
            }
        }
        return false
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

    private fun createNotificationChannel(): String{
        var channelId = "old"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channelId = "destination_alarm_service"
            val channelName = "Destination Alarm Service"
            val chan = NotificationChannel(channelId,
                    channelName, NotificationManager.IMPORTANCE_NONE)
            chan.lightColor = Color.BLUE
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            service.createNotificationChannel(chan)
        }
        return channelId
    }

    class NoTapPolygon(map:MapView?) : Polygon(map) {

        override fun onSingleTapConfirmed(e: MotionEvent, mapView:MapView ): Boolean {
            return false
        }
    }

    class LocationService2 : Service() {


        private fun vibrateIt(){
            if (Build.VERSION.SDK_INT >= 26) {
                vib!!.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 1000), 1))
            }
            else {
                vib!!.vibrate(360000000) // 100 hours xD
            }
        }

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
                                            if (check) {
                                                val results = FloatArray(3)
                                                distanceBetween(loc.latitude, loc.longitude, targetMarker!!.position.latitude, targetMarker!!.position.longitude, results)
                                                if (results[0] <= minDist) {
                                                    ringtone!!.play()
                                                    vibrateIt()
                                                    map!!.overlays.remove(circle)
                                                    map!!.overlays.remove(targetMarker)
                                                    check = false
                                                    map!!.invalidate()
                                                    val builder = AlertDialog.Builder(this_)
                                                    builder.setTitle("WAKE UP!")
                                                            .setMessage("Stop alarm?")
                                                            .setPositiveButton(android.R.string.yes) { dialog, _ ->
                                                                dialog.cancel()
                                                            }.setIcon(android.R.drawable.ic_dialog_alert)
                                                            .setOnCancelListener {
                                                                ringtone!!.stop()
                                                                vib!!.cancel()
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
                                        startForeground(16, notif)
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

    private class ReverseGeocodingAsyncTask(name:String, m:Int) : AsyncTask<String, Int, ArrayList<Address>>() {

        var name:String = name
        var maxRes:Int = m

        override fun doInBackground(vararg params: String?) : ArrayList<Address>{
            var foundAddresses = ArrayList<Address>()
            val geocoderNominatim = GeocoderNominatim("name=George Stavrinos,email=stavrinosgeo@gmail.com,app=destination_alarm")
            geocoderNominatim.setService("https://nominatim.openstreetmap.org/")
            try{
                    foundAddresses.addAll(geocoderNominatim.getFromLocationName(name, maxRes))
                    return foundAddresses
                } catch(e:Exception){

                }
                return foundAddresses
            }
        }

}

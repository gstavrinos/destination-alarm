package gstavrinos.destinationalarm

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.*
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import android.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import android.media.*
import android.net.Uri
import android.os.*
import android.support.v4.app.NotificationCompat
import android.support.v7.app.AppCompatActivity
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.SeekBar.OnSeekBarChangeListener
import com.tbruyelle.rxpermissions2.RxPermissions
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.cachemanager.CacheManager
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
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(){
    private var settings:SharedPreferences? = null
    private var editor: SharedPreferences.Editor? = null
    private var favourites: MutableSet<String>? = TreeSet()
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
        superDirty = this
        Configuration.getInstance().load(superDirty, PreferenceManager.getDefaultSharedPreferences(superDirty))
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
        @SuppressLint("CommitPrefEdits")
        editor = settings!!.edit()


        val snd = settings!!.getString("alarm_sound", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString())
        val alarmFile = File(snd)
        notification = if (!alarmFile.exists()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) else Uri.parse(snd)

        ringtone = RingtoneManager.getRingtone(superDirty, notification)
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
        val arraylists = updateFavLocArrayLists(favourites!!)
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
        map!!.setUseDataConnection(!settings!!.getBoolean("offline_mode", false))

        val settingsbutton:ImageButton = findViewById(R.id.settings_button)

        settingsbutton.setOnClickListener {
            showPopupSettings()
        }

        val searchButton:ImageButton = findViewById(R.id.search_button)

        searchButton.setOnClickListener {
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

        mLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        mLocationOverlay!!.enableMyLocation()
        mLocationOverlay!!.enableFollowLocation()
        mLocationOverlay!!.isOptionsMenuEnabled = true
        map!!.overlays.add(mLocationOverlay)

        targetMarker = Marker(map)
        targetMarker!!.icon =  resources.getDrawable(R.drawable.map_marker_icon, this.theme)

        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun longPressHelper(p: GeoPoint?): Boolean {
                addTargetMarker(p!!.latitude, p.longitude)
                return true
            }

            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
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

        val vibrateReceiver = object: BroadcastReceiver() {
            override fun onReceive(context:Context, intent:Intent) {
                if(intent.action == Intent.ACTION_SCREEN_OFF && vibrating) {
                    vibrateIt()
                }
            }
        }

        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        Configuration.getInstance().tileDownloadThreads = 12
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 10000000000 // 10TB

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(vibrateReceiver, filter)

        locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val serviceIntent = Intent(this, LocationService().javaClass)
        bindService(serviceIntent, mConnection!!, Context.BIND_AUTO_CREATE)

        val downloadMapButton = findViewById<ImageButton>(R.id.download_maps_button)
        downloadMapButton.setOnClickListener {
            Configuration.getInstance().expirationOverrideDuration = 31557600000000 // 1000 years!
            Log.e("DOWNLOAD BUTTON", "DOWNLOAD BUTTON")
            val cacheManager = CacheManager(map)
            val zoomMin = map!!.zoomLevelDouble.toInt()
            var zoomMax = map!!.maxZoomLevel.toInt() - 10
            zoomMax = if (zoomMax < zoomMin) zoomMin else zoomMax
            val t = cacheManager.downloadAreaAsync(superDirty, map!!.boundingBox, zoomMin, zoomMax)
            Log.e("STATUSSSSSSS",t.status.toString())
        }
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
                editor!!.apply()
                ringtone = RingtoneManager.getRingtone(superDirty, notification)
                ringtone!!.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED).build()
                Toast.makeText(superDirty, "New alarm sound set!", Toast.LENGTH_SHORT).show()
            }
            else{
                Toast.makeText(superDirty, "The sound of the alarm cannot be set to none!", Toast.LENGTH_LONG).show()
            }
        }
        if(requestCode == 1){
            if(!locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)){
                showGPSDialog()
            }
            else{
                recreate()
            }
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        unbindService(mConnection!!)
    }


    fun vibrateIt(){
        vibrating = true
        if (Build.VERSION.SDK_INT >= 26) {
            vib!!.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 1000), 1))
        }
        else {
            @Suppress("DEPRECATION")
            vib!!.vibrate(360000000) // 100 hours xD
        }
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
                editor!!.apply()
            }

            override fun onStartTrackingTouch(seekBar:SeekBar) {}

            override fun onProgressChanged(seekBar:SeekBar, progress:Int,fromUser:Boolean) {
                radiusValue.text = (progress+20).toString()
            }
        })

        val offlineSelection = popupView.findViewById<CheckBox>(R.id.offline_selection)
        offlineSelection.isChecked = settings!!.getBoolean("offline_mode", false)
        offlineSelection.setOnClickListener(object: View.OnClickListener{
            override fun onClick(v: View?) {
                map!!.setUseDataConnection(!offlineSelection.isChecked)
                editor!!.putBoolean("offline_mode", offlineSelection.isChecked)
                editor!!.apply()
            }

        })

        val deleteCacheButton = popupView.findViewById<Button>(R.id.clean_cache_button)
        deleteCacheButton.setOnClickListener(object: View.OnClickListener{
            override fun onClick(v: View?) {
                map!!.getTileProvider().clearTileCache()
                Toast.makeText(superDirty, "Cache was deleted successfully!", Toast.LENGTH_LONG).show()
            }

        })

        val alarmSoundButton = popupView.findViewById<Button>(R.id.alarm_sound_button)
        alarmSoundButton.setOnClickListener(object: View.OnClickListener{
            override fun onClick(v: View?) {
                val sndmngtIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                sndmngtIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                sndmngtIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Alarm Sound")
                sndmngtIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, notification)
                sndmngtIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                startActivityForResult(sndmngtIntent, 5)
            }

        })

        val alarmSoundSelection: RadioGroup = popupView.findViewById(R.id.sound_source_selection)
        if(settings!!.getBoolean("useSpeaker", true)){
            alarmSoundSelection.check((R.id.speaker_radio))
        }
        else{
            alarmSoundSelection.check((R.id.headphones_radio))
        }

        alarmSoundSelection.setOnCheckedChangeListener(object: RadioGroup.OnCheckedChangeListener{
            override fun onCheckedChanged(group: RadioGroup?, checkedId: Int) {
                editor!!.putBoolean("useSpeaker", checkedId == R.id.speaker_radio)
                editor!!.apply()
                //audioManager!!.isSpeakerphoneOn = checkedId == R.id.speaker_radio
            }

        })


        val bg = ColorDrawable(0xCC333333.toInt())
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
                Toast.makeText(superDirty, "Location name cannot contain a \"/\"!", Toast.LENGTH_LONG).show()
            }
            else if(favn.text.isEmpty()){
                Toast.makeText(superDirty, "Location name cannot be empty!", Toast.LENGTH_LONG).show()
            }
            else{
                val stringToAdd = locationStringGenerator(favn.text.toString(), targetMarker)
                if(favLocs.contains(favn.text.toString())){
                    Toast.makeText(superDirty, "Already in favourites!", Toast.LENGTH_LONG).show()
                }
                else{
                    favourites!!.add(stringToAdd)
                    val arraylists = updateFavLocArrayLists(favourites!!)
                    favLocs.clear()
                    favLocs.addAll(arraylists.first)
                    favLats.clear()
                    favLats.addAll(arraylists.second)
                    favLons.clear()
                    favLons.addAll(arraylists.third)
                    editor!!.clear()
                    editor!!.putStringSet("favourites", favourites)
                    editor!!.apply()
                    Toast.makeText(superDirty, "Location added to favourites!", Toast.LENGTH_LONG).show()
                    popupWindow.dismiss()
                }

            }
        }

        cancelbutton.setOnClickListener{
            popupWindow.dismiss()
        }


        val bg = ColorDrawable(0xCC333333.toInt())
        popupWindow.setBackgroundDrawable(bg)
        popupWindow.showAtLocation(findViewById(android.R.id.content), Gravity.CENTER, 0, 0)

    }

    private fun showPopupFav() {
        if(!favLocs.isEmpty()){
            val popupView: View = layoutInflater.inflate(R.layout.favourites_list, null)

            val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            popupWindow.isFocusable = true

            val favlist = popupView.findViewById<ListView>(R.id.fav_list)
            val adapter = ArrayAdapter<String>(superDirty,android.R.layout.simple_list_item_1, favLocs)
            favlist.adapter = adapter

            favlist.setOnItemLongClickListener{ _, _, position, _ ->
                val builder = AlertDialog.Builder(superDirty)
                builder.setTitle("WARNING!")
                        .setMessage("Remove location from favourites?")
                        .setPositiveButton(android.R.string.yes) { dialog, _ ->
                            favourites!!.remove(locationStringGenerator(favLocs[position], favLats[position], favLons[position]))
                            val arraylists = updateFavLocArrayLists(favourites!!)
                            favLocs.clear()
                            favLocs.addAll(arraylists.first)
                            favLats.clear()
                            favLats.addAll(arraylists.second)
                            favLons.clear()
                            favLons.addAll(arraylists.third)
                            adapter.notifyDataSetChanged()
                            editor!!.clear()
                            editor!!.putStringSet("favourites", favourites)
                            editor!!.apply()
                            Toast.makeText(superDirty, "Location removed from favourites!", Toast.LENGTH_LONG).show()
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
            }


            val bg = ColorDrawable(0xCC333333.toInt())
            popupWindow.setBackgroundDrawable(bg)
            popupWindow.showAtLocation(findViewById(android.R.id.content), Gravity.CENTER, 0, 0)
        }
        else{
            Toast.makeText(superDirty, "No favourite locations saved. Click on a marker to save it!", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPopupAddressList() {
        val popupView: View = layoutInflater.inflate(R.layout.address_list, null)

        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        popupWindow.isFocusable = true

        val addresses = ArrayList<Address>()
        val addressesString = ArrayList<String>()
        val addressList = popupView.findViewById<ListView>(R.id.address_list)
        val adapter = ArrayAdapter<String>(superDirty,android.R.layout.simple_list_item_1, addressesString)
        addressList.adapter = adapter

        val searchButton = popupView.findViewById<ImageButton>(R.id.search_button)
        searchButton.isEnabled = false

        val addressSearch = popupView.findViewById<EditText>(R.id.address_search)

        addressSearch.addTextChangedListener(object: TextWatcher{
            override fun afterTextChanged(s: Editable?) {}

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchButton.isEnabled = !s!!.trim().isEmpty()
            }

        })

        val progressThingy = popupView.findViewById<ProgressBar>(R.id.progressThingy)
        progressThingy.visibility = View.GONE

        searchButton.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(addressSearch.windowToken, 0)
            searchButton.isEnabled = false
            addressSearch.clearFocus()
            progressThingy.visibility = View.VISIBLE
            try {
                addresses.clear()
                addressesString.clear()
                adapter.notifyDataSetChanged()
                thread {
                    try {
                        val searchAddressTask = ReverseGeocodingAsyncTask(addressSearch.text.toString(), 50)
                        searchAddressTask.execute()
                        addresses.addAll(searchAddressTask.get(30, TimeUnit.SECONDS))
                        runOnUiThread {
                            progressThingy.visibility = View.GONE
                        }
                        if (addresses.isEmpty()) {
                            runOnUiThread {
                                Toast.makeText(superDirty, "No locations found! Check your criteria and your internet connection", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            for (i in addresses) {
                                var nextAddress = ""
                                for (j in 0..i.maxAddressLineIndex) {
                                    nextAddress += i.getAddressLine(j) + ", "
                                }
                                nextAddress = nextAddress.removeRange(nextAddress.length - 2, nextAddress.length)
                                addressesString.add(nextAddress)
                            }
                            runOnUiThread {
                                adapter.notifyDataSetChanged()
                            }
                        }
                        runOnUiThread {
                            searchButton.isEnabled = true
                        }
                    }
                    catch(e:Exception){
                        runOnUiThread {
                            progressThingy.visibility = View.GONE
                            Toast.makeText(superDirty, "Connection problem! The server took too long to respond. Please check your Internet connection.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            catch(e:Exception){
                progressThingy.visibility = View.GONE
                Toast.makeText(superDirty, "Connection problem! The server took too long to respond. Please check your Internet connection.", Toast.LENGTH_LONG).show()
            }
        }

        addressList.setOnItemClickListener{ _, _, position, _ ->
            addTargetMarker(addresses[position].latitude, addresses[position].longitude)
            mLocationOverlay!!.disableFollowLocation()
            map!!.setExpectedCenter(GeoPoint(addresses[position].latitude, addresses[position].longitude))
            popupWindow.dismiss()
        }


        val bg = ColorDrawable(0xCC333333.toInt())
        popupWindow.setBackgroundDrawable(bg)
        popupWindow.showAtLocation(findViewById(android.R.id.content), Gravity.TOP, 0, 0)
    }

    fun showGPSDialog(){
        val builder = AlertDialog.Builder(superDirty)
        builder.setTitle("GPS not enabled!")
                .setMessage("The app requires GPS in order to work properly. Press ok to continue to settings to enable GPS or cancel to exit.")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val locIntent = Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    startActivityForResult(locIntent, 1)
                }
                .setNegativeButton(android.R.string.no) { _, _ ->
                    finish()
                }.setIcon(android.R.drawable.ic_dialog_alert)
                .setOnCancelListener {
                    builder.show()
                }
                .show()
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
        Toast.makeText(superDirty, "New alarm destination set!", Toast.LENGTH_SHORT).show()
    }

    private fun locationStringGenerator(s:String, targetMarker:Marker?) : String{
        return s + "/" + targetMarker!!.position.latitude.toString() + "/" + targetMarker!!.position.longitude.toString()

    }

    private fun locationStringGenerator(s:String, lat:Double, lon:Double) : String{
        return s + "/" + lat.toString() + "/" + lon.toString()
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

}

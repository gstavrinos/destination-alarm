package gstavrinos.destinationalarm

import android.app.Notification
import android.location.LocationManager
import android.media.Ringtone
import android.os.Vibrator
import com.tbruyelle.rxpermissions2.RxPermissions
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker


var map: MapView? = null
var locationManager: LocationManager? = null
var ringtone: Ringtone? = null
var targetMarker: Marker? = null
var minDist:Int = 1000
var circle = NoTapPolygon(null)
var check:Boolean = false
var notif: Notification? = null
var vib: Vibrator? = null
var vibrating = false
var rxPermissions: RxPermissions? = null

var superDirty:MainActivity? = null
package gstavrinos.destinationalarm

import android.Manifest
import android.app.AlertDialog
import android.os.Bundle
import android.content.Intent
import android.os.IBinder
import android.app.Service
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager

class LocationService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        val isGPSEnabled = locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)

        val disposable = rxPermissions!!.request(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE)
                .subscribe { granted ->
                    if (granted) {
                        if (isGPSEnabled) {
                            if (locationManager != null) {

                                val gpsLocationListener = object : LocationListener {
                                    override fun onLocationChanged(loc: Location) {
                                        if (check) {
                                            val results = FloatArray(3)
                                            Location.distanceBetween(loc.latitude, loc.longitude, targetMarker!!.position.latitude, targetMarker!!.position.longitude, results)
                                            if (results[0] <= minDist) {
                                                ringtone!!.play()
                                                superDirty!!.vibrateIt()
                                                map!!.overlays.remove(circle)
                                                map!!.overlays.remove(targetMarker)
                                                check = false
                                                map!!.invalidate()
                                                val builder = AlertDialog.Builder(superDirty)
                                                builder.setTitle("WAKE UP!")
                                                        .setMessage("Stop alarm?")
                                                        .setPositiveButton(android.R.string.yes) { dialog, _ ->
                                                            dialog.cancel()
                                                        }.setIcon(android.R.drawable.ic_dialog_alert)
                                                        .setOnCancelListener {
                                                            ringtone!!.stop()
                                                            vib!!.cancel()
                                                            vibrating = false
                                                        }
                                                        .show()
                                            }
                                        }
                                    }

                                    @Deprecated("Deprecated in Java")
                                    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
                                    override fun onProviderEnabled(provider: String) {}
                                    override fun onProviderDisabled(provider: String) {
                                        superDirty!!.showGPSDialog()
                                    }
                                }
                                try {
                                    locationManager!!.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                                            1, 1f,
                                            gpsLocationListener)
                                    startForeground(16, notif)
                                }
                                catch (e: SecurityException) {
                                    // TODO handle this!
                                }
                            }
                        }
                        else {
                            superDirty!!.showGPSDialog()

                        }
                    }
                    else {
                        val builder = AlertDialog.Builder(superDirty)
                        builder.setTitle("PERMISSIONS ERROR!")
                                .setMessage("The app cannot work without the required permissions. Exiting...")
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    superDirty!!.finish()
                                }.setIcon(android.R.drawable.ic_dialog_alert)
                                .setOnCancelListener {
                                    superDirty!!.finish()
                                }
                                .show()
                    }
                }
        return null
    }
}
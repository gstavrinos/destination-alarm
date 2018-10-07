package gstavrinos.destinationalarm

import android.view.MotionEvent
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

class NoTapPolygon(map: MapView?) : Polygon(map) {

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        return false
    }
}
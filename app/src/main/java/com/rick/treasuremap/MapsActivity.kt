package com.rick.treasuremap

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.rick.treasuremap.databinding.ActivityMapsBinding
import kotlin.random.Random

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var map: GoogleMap
    private lateinit var binding: ActivityMapsBinding

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var lastLocation: Location


    companion object {
        const val MINIMUM_RECOMMENDED_RADIUS = 100f // 1F = 1Meter on the map
        const val GEOFENCE_KEY = "TreasureLocation"
    }

    private val geofenceList = arrayListOf<Geofence>()
    private var treasureLocation: LatLng? = null
    private lateinit var geofencingClient: GeofencingClient

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            endTreasureHunt()
            Toast.makeText(this@MapsActivity, getString(R.string.treasure_found), Toast.LENGTH_LONG)
                .show()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        if (!LocationPermissionHelper.hasLocationPermission(this)) LocationPermissionHelper.requestPermissions(
            this
        )

        geofencingClient = LocationServices.getGeofencingClient(this)

        registerReceiver(broadcastReceiver, IntentFilter("GEOFENCE_ENTERED"))

    }

    @SuppressLint("MissingPermission")
    private fun generateTreasureLocation() {
        val choiceList = listOf(true, false)
        var choice = choiceList.random()
        val treasureLat =
            if (choice) lastLocation.latitude + Random.nextFloat() else lastLocation.latitude - Random.nextFloat()
        choice = choiceList.random()
        val treasureLng =
            if (choice) lastLocation.longitude + Random.nextFloat() else lastLocation.longitude - Random.nextFloat()
        treasureLocation = LatLng(treasureLat, treasureLng)

        removeTreasureMarker()
        geofenceList.add(
            Geofence.Builder()
                .setRequestId(GEOFENCE_KEY)
                .setCircularRegion(
                    treasureLat,
                    treasureLng,
                    MINIMUM_RECOMMENDED_RADIUS
                )
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()
        )
        try {
            geofencingClient.addGeofences(createGeofencingRequest(), createGeofencePendingIntent())
                .addOnSuccessListener(this) {
                    Toast.makeText(this, getString(R.string.begin_search), Toast.LENGTH_SHORT)
                        .show()

                    val circleOptions = CircleOptions()
                        .strokeColor(Color.DKGRAY)
                        .fillColor(Color.TRANSPARENT)
                        .center(treasureLocation!!)
                        .radius(MINIMUM_RECOMMENDED_RADIUS.toDouble())
                    map.addCircle(circleOptions)
                    //TODO: Start the timer and display an initial hint
                }
                .addOnFailureListener(this) {
                    Toast.makeText(
                        this,
                        getString(R.string.treasure_error, it.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
        } catch (ignore: SecurityException) {
        }
    }

    private fun createGeofencePendingIntent(): PendingIntent {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createGeofencingRequest(): GeofencingRequest {
        return GeofencingRequest.Builder().apply {
            setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            addGeofences(geofenceList)
        }.build()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        map.uiSettings.isZoomControlsEnabled = true

        prepareMap()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (!LocationPermissionHelper.hasLocationPermission(this))
            LocationPermissionHelper.requestPermissions(this)
        else prepareMap()
    }


    @SuppressLint("MissingPermission")
    private fun prepareMap() {
        if (LocationPermissionHelper.hasLocationPermission(this)) {
            map.isMyLocationEnabled = true

            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.apply {
                    lastLocation = location
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 12f))
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(broadcastReceiver)
    }
}
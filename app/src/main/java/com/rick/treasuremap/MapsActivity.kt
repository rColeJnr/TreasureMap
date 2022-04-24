package com.rick.treasuremap

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.rick.treasuremap.databinding.ActivityMapsBinding
import java.io.IOException
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

    private fun endTreasureHunt() {
        geofencingClient.removeGeofences(createGeofencePendingIntent()).run {
            addOnSuccessListener {
                geofenceList.clear()
            }
            addOnFailureListener {  }
        }
        if (treasureMarker == null) treasureMarker = placeMarkerOnMap(treasureLocation!!)
        binding.treasureHuntBtn.text = getString(R.string.start_treasure_hunt)
        binding.hintBtn.visibility = View.INVISIBLE
        huntStarted = false
        //TODO: Cancel the timer here
        binding.timer.text = getString(R.string.hunt_ended)
    }

    private fun placeMarkerOnMap(treasureLocation: LatLng): Marker? {
        val markerOptions = MarkerOptions()
            .position(treasureLocation)
            .title(getAddress(treasureLocation))
        return map.addMarker(markerOptions)
    }

    private fun getAddress(latLng: LatLng): String? {
        var addressText = getString(R.string.no_address)

        try {
            val addresses = Geocoder(this).getFromLocation(latLng.latitude, latLng.longitude, 1)
            if (!addressText.isNullOrEmpty()) {
                addressText = addresses[0].getAddressLine(0) ?: addressText
            }
        } catch (e:IOException){
            addressText = getString(R.string.address_error)
        }
        return addressText
    }

    private var treasureMarker: Marker? = null
    private var huntStarted = false

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

        binding.apply {
            treasureHuntBtn.setOnClickListener {
                when {
                    !this@MapsActivity::lastLocation.isInitialized -> Toast.makeText(this@MapsActivity, getString(R.string.location_error), Toast.LENGTH_LONG).show()
                    huntStarted -> endTreasureHunt()
                    else -> {
                        generateTreasureLocation()
                        treasureHuntBtn.text = getString(R.string.end_the_treasuer_hunt)
                        hintBtn.visibility = View.VISIBLE
                        huntStarted = true
                    }
                }
            }
        }
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

    private fun removeTreasureMarker() {
        if (treasureMarker != null){
            treasureMarker?.remove()
            treasureMarker = null
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
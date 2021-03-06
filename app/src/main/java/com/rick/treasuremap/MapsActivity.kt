package com.rick.treasuremap

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.common.api.ResolvableApiException
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

class MapsActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener {

    private lateinit var map: GoogleMap
    private lateinit var binding: ActivityMapsBinding

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var lastLocation: Location

    private var receivingLocationUpdates = false
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest

    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var isRotating = false
    private lateinit var sensorManager: SensorManager


    companion object {
        const val MINIMUM_RECOMMENDED_RADIUS = 100f // 1F = 1Meter on the map
        const val GEOFENCE_KEY = "TreasureLocation"
    }

    private val geofenceList = arrayListOf<Geofence>()
    private var treasureLocation: LatLng? = null
    private lateinit var geofencingClient: GeofencingClient

    private val timer = object : CountDownTimer(3600000, 1000) {
        override fun onTick(p0: Long) {
            binding.timer.text = getString(R.string.timer, p0 / 1000)
        }

        override fun onFinish() {
            endTreasureHunt()
            binding.timer.text = getString(R.string.times_up)
        }
    }

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

        if(!LocationPermissionHelper.hasLocationPermission(this))LocationPermissionHelper.requestPermissions(this)

        geofencingClient = LocationServices.getGeofencingClient(this)

        registerReceiver(broadcastReceiver, IntentFilter("GEOFENCE_ENTERED"))

        binding.apply {
            treasureHuntBtn.setOnClickListener {
                when {
                    !this@MapsActivity::lastLocation.isInitialized -> Toast.makeText(
                        this@MapsActivity,
                        getString(R.string.location_error),
                        Toast.LENGTH_LONG
                    ).show()
                    huntStarted -> endTreasureHunt()
                    else -> {
                        generateTreasureLocation()
                        treasureHuntBtn.text = getString(R.string.end_the_treasuer_hunt)
                        hintBtn.visibility = View.VISIBLE
                        huntStarted = true
                    }
                }
            }
            hintBtn.setOnClickListener {
                showHint()
            }
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            sensorManager.registerListener(
                this,
                magneticField,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }
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
        } catch (e: IOException) {
            addressText = getString(R.string.address_error)
        }
        return addressText
    }

    private var treasureMarker: Marker? = null
    private var huntStarted = false

    override fun onResume() {
        super.onResume()
        if (!receivingLocationUpdates) createLocationRequest()
    }

    private fun createLocationRequest() {
        locationRequest = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        val locationSettingRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .build()
        val client = LocationServices.getSettingsClient(this)
        client.checkLocationSettings(locationSettingRequest).apply {
            addOnSuccessListener {
                receivingLocationUpdates = true
                startLocationUpdates()
            }
            addOnFailureListener {
                if (it is ResolvableApiException) {
                    registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                        if (result.resultCode == RESULT_OK) {
                            receivingLocationUpdates = true
                            startLocationUpdates()
                        }
                    }.launch(IntentSenderRequest.Builder(it.resolution).build())
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            locationCallback = object : LocationCallback() {
                override fun onLocationAvailability(p0: LocationAvailability) {

                }

                override fun onLocationResult(p0: LocationResult) {
                    super.onLocationResult(p0)
                    lastLocation = p0.lastLocation
                }
            }
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (ignore: SecurityException) {
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
                    timer.start()
                    showHint()
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
        if (treasureMarker != null) {
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

    private fun showHint() {
        if (treasureLocation != null && this::lastLocation.isInitialized) {
            val latDir =
                if (treasureLocation!!.latitude > lastLocation.latitude) getString(R.string.north)
                else getString(R.string.south)
            val lonDir =
                if (treasureLocation!!.longitude > lastLocation.longitude) getString(R.string.east)
                else getString(R.string.west)
            Toast.makeText(this, getString(R.string.direction, latDir, lonDir), Toast.LENGTH_SHORT)
                .show()
        }
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

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> System.arraycopy(
                event.values,
                0,
                accelerometerReading,
                0,
                accelerometerReading.size
            )
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(
                event.values,
                0,
                magnetometerReading,
                0,
                magnetometerReading.size
            )
        }
        if (!isRotating) updateOrientationAngles()
    }

    private fun updateOrientationAngles() {
        SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            accelerometerReading,
            magnetometerReading
        )
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val degrees = (Math.toDegrees(orientationAngles[0].toDouble()))

        val newRotation = degrees.toFloat() * -1
        val rotationChange = newRotation - binding.compass.rotation

        binding.compass.animate().apply {
            isRotating = true
            rotationBy(rotationChange)
            duration = 500
            setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    isRotating = false
                }
            })
        }.start()
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
    }

    private fun endTreasureHunt() {
        geofencingClient.removeGeofences(createGeofencePendingIntent()).run {
            addOnSuccessListener {
                geofenceList.clear()
            }
            addOnFailureListener { }
        }
        if (treasureMarker == null) treasureMarker = placeMarkerOnMap(treasureLocation!!)
        binding.treasureHuntBtn.text = getString(R.string.start_treasure_hunt)
        binding.hintBtn.visibility = View.INVISIBLE
        huntStarted = false
        timer.cancel()
        binding.timer.text = getString(R.string.hunt_ended)
    }


    override fun onPause() {
        super.onPause()
        if (this::locationCallback.isInitialized) fusedLocationClient.removeLocationUpdates(
            locationCallback
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(broadcastReceiver)
    }
}
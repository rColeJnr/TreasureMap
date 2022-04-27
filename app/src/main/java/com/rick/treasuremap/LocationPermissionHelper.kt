package com.rick.treasuremap

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object LocationPermissionHelper {

    private const val BACKGROUND_LOCATION_PERMISSION =Manifest.permission.ACCESS_BACKGROUND_LOCATION
    private const val COARSE_LOCATION_PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION
    private const val FINE_LOCATION_PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION

    fun hasLocationPermission(activity: Activity): Boolean =
        ContextCompat.checkSelfPermission(
            activity,
            FINE_LOCATION_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    activity,
                    BACKGROUND_LOCATION_PERMISSION
                ) == PackageManager.PERMISSION_GRANTED

    fun requestPermissions(activity: Activity){
        if (ActivityCompat.shouldShowRequestPermissionRationale(activity, FINE_LOCATION_PERMISSION)){
            AlertDialog.Builder(activity).apply {
                setMessage(activity.getString(R.string.permission_required))
                setPositiveButton(activity.getString(R.string.ok)) {_,_ ->
                    ActivityCompat.requestPermissions(activity, arrayOf(FINE_LOCATION_PERMISSION, COARSE_LOCATION_PERMISSION, BACKGROUND_LOCATION_PERMISSION), 0)
                }
                show()
            }
        } else {
            ActivityCompat.requestPermissions(activity, arrayOf(FINE_LOCATION_PERMISSION, COARSE_LOCATION_PERMISSION, BACKGROUND_LOCATION_PERMISSION), 0)
        }
    }

}
package com.example.rightposture.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.rightposture.core.pose.Vec3

/**
 * §6.20.1 check 5's gravity vector.
 *
 * Device coordinates: x across the screen, y up the screen, z out of the screen. A phone standing
 * upright facing the patient reads gravity almost entirely on y, so `|z| / |g|` is the sine of how
 * far the screen plane is tilted off vertical - which is exactly what the plan's threshold is.
 *
 * Low-passed the standard way, because the raw accelerometer is gravity *plus* whatever the phone
 * is doing; a phone being set down should not fail the check for the second it is moving.
 *
 * Read from the camera analysis thread and written from the sensor thread, hence `@Volatile`. It is
 * a single reference to an immutable value, so no lock is needed.
 */
class GravitySensor(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    private var gravity: Vec3? = null

    fun start() {
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    /**
     * Null until the first reading arrives, and on a device with no accelerometer at all. The gate
     * treats that as a failed check rather than a passed one: §6.6's rule is that not knowing is
     * never the same as agreeing.
     */
    fun current(): Vec3? = gravity

    override fun onSensorChanged(event: SensorEvent) {
        val reading = Vec3(event.values[0], event.values[1], event.values[2])
        val previous = gravity
        gravity = if (previous == null) {
            reading
        } else {
            Vec3(
                ALPHA * previous.x + (1 - ALPHA) * reading.x,
                ALPHA * previous.y + (1 - ALPHA) * reading.y,
                ALPHA * previous.z + (1 - ALPHA) * reading.z,
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val ALPHA = 0.8f
    }
}

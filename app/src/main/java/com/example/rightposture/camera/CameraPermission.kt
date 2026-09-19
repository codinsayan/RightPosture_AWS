package com.example.rightposture.camera

/**
 * What the camera screen should show. Kept free of Android types so it can be unit tested; the
 * Activity supplies the three booleans and renders the result.
 */
enum class CameraPermissionState {
    /** Permission held. Bind the camera. */
    GRANTED,

    /** Never asked. Ask immediately, no explanation needed. */
    NEEDS_REQUEST,

    /** Denied once. Android will still show the dialog, so explain why first. */
    NEEDS_RATIONALE,

    /**
     * Denied to the point where the system dialog no longer appears. Asking again is a no-op, so
     * the only honest action is to send the user to system settings.
     */
    BLOCKED,
}

/**
 * @param granted result of `checkSelfPermission`
 * @param askedThisSession whether a request has already been launched and answered
 * @param shouldShowRationale Android's `shouldShowRequestPermissionRationale`
 */
fun cameraPermissionState(
    granted: Boolean,
    askedThisSession: Boolean,
    shouldShowRationale: Boolean,
): CameraPermissionState = when {
    granted -> CameraPermissionState.GRANTED
    shouldShowRationale -> CameraPermissionState.NEEDS_RATIONALE
    // Android reports no rationale both before the first ask and after a permanent denial. The
    // difference is whether we have already asked, which only this side knows.
    askedThisSession -> CameraPermissionState.BLOCKED
    else -> CameraPermissionState.NEEDS_REQUEST
}

/** Asking again only reaches a dialog in these states. In [CameraPermissionState.BLOCKED] it does nothing. */
fun canRequest(state: CameraPermissionState) =
    state == CameraPermissionState.NEEDS_REQUEST || state == CameraPermissionState.NEEDS_RATIONALE

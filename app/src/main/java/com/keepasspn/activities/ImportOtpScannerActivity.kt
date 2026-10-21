/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassPN.
 *
 *  KeePassPN is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassPN is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassPN.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.keepasspn.activities

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MenuItem
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.keepasspn.R
import com.keepasspn.activities.legacy.DatabaseLockActivity
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.model.OtpModel
import com.keepasspn.otp.GoogleAuthenticatorMigration
import com.keepasspn.otp.OtpEntryFields
import com.keepasspn.services.DatabaseTaskNotificationService
import com.keepasspn.tasks.ActionRunnable
import com.keepasspn.view.QrViewfinderView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scans QR codes with the back camera, collects the accounts they contain, then asks the user
 * which of them to import.
 *
 * Supported codes:
 * - Google Authenticator export (otpauth-migration://), possibly split in several batches
 * - Single otpauth:// codes, as shown by most authenticator apps
 *
 * Everything runs on the device: no network access is needed.
 */
class ImportOtpScannerActivity : DatabaseLockActivity(), SurfaceHolder.Callback {

    private lateinit var container: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private lateinit var doneButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val decoding = AtomicBoolean(false)
    private val reader = QRCodeReader()
    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true
    )

    // Scan state, shared between the camera thread and the UI thread
    private val lock = Any()
    private val scannedTexts = HashSet<String>()
    private val singleAccounts = ArrayList<OtpModel>()
    private val migrationBatches = HashMap<Int, List<OtpModel>>()
    private var migrationId: Int? = null
    private var migrationSize = 0
    private var migrationSkipped = 0
    private var statusNote: Int? = null

    // Square of the frame read by the decoder, in frame coordinates (decodeSide 0 means the whole frame)
    @Volatile private var decodeLeft = 0
    @Volatile private var decodeTop = 0
    @Volatile private var decodeSide = 0
    @Volatile private var resultShown = false

    private var handedOver = false
    private var camera: Camera? = null
    private var surfaceReady = false
    private var resumed = false
    private var permissionAsked = false

    override fun manageDatabaseInfo(): Boolean = true

    override fun viewToInvalidateTimeout(): View? {
        return findViewById(R.id.import_otp_camera_container)
    }

    override fun finishActivityIfReloadRequested(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_otp_scanner)

        val toolbar: Toolbar = findViewById(R.id.import_otp_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.import_otp_title)

        container = findViewById(R.id.import_otp_camera_container)
        surfaceView = findViewById(R.id.import_otp_preview)
        statusView = findViewById(R.id.import_otp_status)
        doneButton = findViewById(R.id.import_otp_done)
        doneButton.setOnClickListener { showResult() }
        surfaceView.holder.addCallback(this)

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (hasCameraPermission()) {
            maybeStartCamera()
        } else if (!permissionAsked) {
            permissionAsked = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onPause() {
        resumed = false
        stopCamera()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                maybeStartCamera()
            } else {
                showToast(R.string.import_otp_permission_denied)
                finish()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        maybeStartCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // The camera is configured once in openCamera, nothing to do here
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopCamera()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDatabaseActionFinished(
        database: ContextualDatabase,
        actionTask: String,
        result: ActionRunnable.Result
    ) {
        if (actionTask == DatabaseTaskNotificationService.ACTION_DATABASE_IMPORT_OTP_TASK) {
            if (result.isSuccess) {
                showToast(R.string.import_otp_imported)
            } else {
                showToast(R.string.import_otp_import_failed)
            }
            finish()
        } else {
            super.onDatabaseActionFinished(database, actionTask, result)
        }
    }

    override fun onDestroy() {
        decodeExecutor.shutdownNow()
        if (!handedOver) {
            // Nothing was handed to the database, drop the secrets kept in memory
            synchronized(lock) {
                singleAccounts.forEach { it.clear() }
                migrationBatches.values.forEach { accounts -> accounts.forEach { it.clear() } }
            }
        }
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun maybeStartCamera() {
        if (!resumed || !surfaceReady || camera != null || resultShown || !hasCameraPermission()) return
        openCamera(surfaceView.holder)
    }

    private fun openCamera(holder: SurfaceHolder) {
        val cameraId = findBackCameraId()
        if (cameraId < 0) {
            showToast(R.string.import_otp_no_camera)
            return
        }
        var openedCamera: Camera? = null
        try {
            val cam = Camera.open(cameraId)
            openedCamera = cam
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(cameraId, info)

            val parameters = cam.parameters
            chooseFrameSize(parameters.supportedPreviewSizes)?.let {
                parameters.setPreviewSize(it.width, it.height)
            }
            if (parameters.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) {
                parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            cam.parameters = parameters
            cam.setDisplayOrientation(displayOrientation(info))

            val frameSize = cam.parameters.previewSize
            val frameWidth = frameSize.width
            val frameHeight = frameSize.height
            fitPreview(frameWidth, frameHeight)
            cam.setPreviewDisplay(holder)
            cam.setPreviewCallback { data, _ -> onFrame(data, frameWidth, frameHeight) }
            cam.startPreview()
            camera = cam
        } catch (e: Exception) {
            openedCamera?.release()
            showToast(R.string.import_otp_camera_error)
        }
    }

    private fun stopCamera() {
        val cam = camera ?: return
        camera = null
        try {
            cam.setPreviewCallback(null)
            cam.stopPreview()
        } catch (e: RuntimeException) {
            // The camera may already be unusable, release it anyway
        }
        cam.release()
    }

    private fun findBackCameraId(): Int {
        val info = Camera.CameraInfo()
        for (cameraId in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(cameraId, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return cameraId
        }
        return -1
    }

    /**
     * Prefer a frame size up to [MAX_FRAME_WIDTH] wide, big enough to read a QR code and
     * small enough to decode quickly on old devices.
     */
    private fun chooseFrameSize(sizes: List<Camera.Size>?): Camera.Size? {
        if (sizes.isNullOrEmpty()) return null
        return sizes.filter { it.width <= MAX_FRAME_WIDTH }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
    }

    @Suppress("DEPRECATION")
    private fun displayOrientation(info: Camera.CameraInfo): Int {
        val degrees = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (info.orientation - degrees + 360) % 360
    }

    /**
     * Called on the camera thread. The frame array is not reused by the camera for
     * setPreviewCallback, so it can be handed to the decoder directly. Frames are dropped
     * while the previous one is still being decoded, to keep the preview smooth.
     */
    private fun onFrame(data: ByteArray, width: Int, height: Int) {
        if (resultShown) return
        if (!decoding.compareAndSet(false, true)) return
        decodeExecutor.execute {
            try {
                val text = decodeQrCode(data, width, height)
                if (text != null) handleScannedText(text)
            } finally {
                decoding.set(false)
            }
        }
    }

    /**
     * Shows the preview at the frame aspect ratio, centered and cropped to the screen, so it is never
     * stretched. The decoder then only reads the centered square of the viewfinder.
     */
    private fun fitPreview(frameWidth: Int, frameHeight: Int) {
        val containerWidth = container.width
        val containerHeight = container.height
        if (containerWidth == 0 || containerHeight == 0 || frameWidth == 0 || frameHeight == 0) {
            // Not laid out yet, read the whole frame
            decodeSide = 0
            return
        }
        // The frame is rotated by the display orientation: its width runs along the screen height
        val scale = max(containerHeight.toFloat() / frameWidth, containerWidth.toFloat() / frameHeight)
        surfaceView.layoutParams = FrameLayout.LayoutParams(
            (frameHeight * scale).roundToInt(),
            (frameWidth * scale).roundToInt(),
            Gravity.CENTER
        )
        // The viewfinder side in screen pixels, converted to frame pixels
        val side = min(
            min(frameWidth, frameHeight).toFloat(),
            QrViewfinderView.VIEWFINDER_FRACTION * containerWidth / scale
        ).roundToInt()
        decodeSide = side
        decodeLeft = (frameWidth - side) / 2
        decodeTop = (frameHeight - side) / 2
    }

    private fun decodeQrCode(data: ByteArray, width: Int, height: Int): String? {
        val source = if (decodeSide > 0) {
            PlanarYUVLuminanceSource(data, width, height, decodeLeft, decodeTop, decodeSide, decodeSide, false)
        } else {
            PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        }
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: ReaderException) {
            null
        }
    }

    /**
     * Called on the decoder thread.
     */
    private fun handleScannedText(rawText: String) {
        val text = rawText.trim()
        synchronized(lock) {
            if (!scannedTexts.add(text)) return // Same code already read
            if (GoogleAuthenticatorMigration.isMigrationUri(text)) {
                addMigrationBatch(text)
            } else {
                val model = OtpEntryFields.parseOTPUri(text)?.otpModel
                if (model != null) {
                    singleAccounts.add(model)
                    statusNote = null
                } else {
                    statusNote = R.string.import_otp_status_invalid
                }
            }
        }
        mainHandler.post {
            updateStatus()
            if (isMigrationComplete()) showResult()
        }
    }

    /**
     * Must be called while holding [lock].
     */
    private fun addMigrationBatch(text: String) {
        val batch = try {
            GoogleAuthenticatorMigration.parse(text)
        } catch (e: IllegalArgumentException) {
            statusNote = R.string.import_otp_status_invalid
            return
        }
        val currentId = migrationId
        if (currentId == null) {
            migrationId = batch.batchId
            migrationSize = maxOf(1, batch.batchSize)
        } else if (currentId != batch.batchId) {
            statusNote = R.string.import_otp_status_other_export
            return
        }
        if (!migrationBatches.containsKey(batch.batchIndex)) {
            migrationBatches[batch.batchIndex] = batch.accounts
            migrationSkipped += batch.skipped
        }
        statusNote = null
    }

    private fun isMigrationComplete(): Boolean {
        synchronized(lock) {
            return migrationId != null && migrationBatches.size >= migrationSize
        }
    }

    private fun updateStatus() {
        val (text, total) = synchronized(lock) {
            val accounts = singleAccounts.size + migrationBatches.values.sumOf { it.size }
            val lines = mutableListOf<String>()
            if (migrationId != null) {
                lines.add(getString(R.string.import_otp_status_batch, migrationBatches.size, migrationSize))
            }
            if (accounts == 0 && migrationId == null) {
                lines.add(getString(R.string.import_otp_status_none))
            } else {
                lines.add(getString(R.string.import_otp_status_accounts, accounts))
            }
            statusNote?.let { lines.add(getString(it)) }
            Pair(lines.joinToString("\n"), accounts)
        }
        statusView.text = text
        doneButton.isEnabled = total > 0
    }

    private fun collectAccounts(): Pair<List<OtpModel>, Int> {
        return synchronized(lock) {
            val list = ArrayList<OtpModel>()
            list.addAll(singleAccounts)
            migrationBatches.keys.sorted().forEach { list.addAll(migrationBatches[it].orEmpty()) }
            Pair(list, migrationSkipped)
        }
    }

    /**
     * Called on the UI thread once codes are collected. Stops the camera and asks which accounts to import.
     */
    private fun showResult() {
        if (resultShown) return
        val (accounts, skipped) = collectAccounts()
        if (accounts.isEmpty()) {
            showToast(R.string.import_otp_nothing)
            return
        }
        resultShown = true
        stopCamera()
        if (skipped > 0) {
            Toast.makeText(this, getString(R.string.import_otp_preview_skipped, skipped), Toast.LENGTH_LONG).show()
        }
        showResultDialog(accounts, BooleanArray(accounts.size) { true })
    }

    private fun showResultDialog(accounts: List<OtpModel>, checked: BooleanArray) {
        val labels: Array<CharSequence> = accounts.map { accountLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.import_otp_result_title, accounts.size))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(R.string.import_otp_prompt_cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.import_otp_result_import) { _, _ ->
                val chosen = accounts.filterIndexed { index, _ -> checked[index] }
                if (chosen.isEmpty()) {
                    showToast(R.string.import_otp_select_one)
                    showResultDialog(accounts, checked)
                } else {
                    handedOver = true
                    importOtpEntries(chosen)
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun accountLabel(model: OtpModel): String {
        val issuer = model.issuer
        val name = model.name
        return when {
            issuer.isEmpty() -> name
            name.isEmpty() -> issuer
            else -> "$issuer ($name)"
        }
    }

    private fun showToast(messageId: Int) {
        Toast.makeText(this, messageId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_CAMERA = 1
        private const val MAX_FRAME_WIDTH = 1280
    }
}

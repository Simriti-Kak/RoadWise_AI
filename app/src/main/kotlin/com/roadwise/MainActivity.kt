package com.roadwise

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.roadwise.databinding.ActivityMainBinding
import com.roadwise.mapping.AdaptiveRoadOverlay
import com.roadwise.models.PotholeData
import com.roadwise.routing.PhotonFeature
import com.roadwise.routing.RouteResult
import com.roadwise.routing.RoutingManager
import com.roadwise.sensors.BumpDetector
import com.roadwise.sensors.RoadFeature
import com.roadwise.utils.DetectionManager
import com.roadwise.utils.PotholeRepository
import com.roadwise.utils.SafetyAlertManager
import com.roadwise.utils.SessionManager
import com.roadwise.utils.Severity
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.*
import android.animation.ValueAnimator
import android.text.Editable
import android.text.TextWatcher

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bumpDetector: BumpDetector
    private lateinit var map: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var detectionManager: DetectionManager
    private lateinit var routingManager: RoutingManager
    private lateinit var safetyAlertManager: SafetyAlertManager
    private lateinit var sharedPrefs: SharedPreferences
    private var switchPulseAnimator: ValueAnimator? = null
    private var destinationMarker: Marker? = null
    private var verifiedPotholeCount = 0
    private var maxSpeedKmh = 0
    private var searchJob: Job? = null
    private var searchResults: List<PhotonFeature> = emptyList()
    private lateinit var adaptiveOverlay: AdaptiveRoadOverlay
    private val routeOverlays = mutableListOf<Polyline>()
    private var currentRoutes: List<RouteResult> = emptyList()

    private val analysisLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val route = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra("SELECTED_ROUTE", RouteResult::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra("SELECTED_ROUTE")
            }
            route?.let { startNavigation(it) }
        } else {
            clearRouteFromMap()
        }
    }

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            PotholeRepository.clearCache()
            updateNavForRole()
            val name = SessionManager.getUserName(this)
            Toast.makeText(this, "Welcome, $name!", Toast.LENGTH_SHORT).show()
            syncAfterLogin()
        }
    }

    private fun syncAfterLogin() {
        PotholeRepository.fetchFromCloud(this) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed && ::map.isInitialized) {
                    refreshMarkers()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val ctx = applicationContext
            Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
            Configuration.getInstance().userAgentValue = packageName

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.statusBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            val internalPrefs = getSharedPreferences("roadwise_internal", MODE_PRIVATE)
            if (!internalPrefs.getBoolean("v2_data_reset", false)) {
                PotholeRepository.clearAll(this)
                internalPrefs.edit { putBoolean("v2_data_reset", true) }
            }

            val allDetections = try {
                PotholeRepository.getAllPotholes(this)
            } catch (e: Exception) {
                Log.e("RoadWise", "Failed to load history", e)
                emptyList()
            }

            verifiedPotholeCount = allDetections.count { it.type == RoadFeature.POTHOLE }
            binding.potholeCount.text = verifiedPotholeCount.toString()

            detectionManager = DetectionManager { type, severity, intensity, loc ->
                if (loc.latitude != 0.0) {
                    val data = PotholeData(loc, type, intensity, severity, System.currentTimeMillis(), emptyList())
                    PotholeRepository.savePothole(this, data)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && ::map.isInitialized) {
                            addHeatmapPoint(data)
                            adaptiveOverlay.refresh()
                            map.controller.animateTo(loc)
                            val severityLabel = severity.name
                            if (type == RoadFeature.POTHOLE) {
                                verifiedPotholeCount++
                                binding.potholeCount.text = verifiedPotholeCount.toString()
                                Toast.makeText(this, "⚠️ $severityLabel POTHOLE DETECTED!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "🏁 $severityLabel SPEED BUMP", Toast.LENGTH_SHORT).show()
                            }
                            map.invalidate()
                        }
                    }
                } else {
                    Log.e("RoadWise", "Detection ignored: No GPS fix")
                }
            }

            map = binding.map
            map.setTileSource(TileSourceFactory.MAPNIK)
            map.setMultiTouchControls(true)
            map.controller.setZoom(19.0)
            map.controller.setCenter(GeoPoint(20.5937, 78.9629))

            locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
            locationOverlay.enableMyLocation()
            locationOverlay.enableFollowLocation()
            map.overlays.add(locationOverlay)

            initAdaptiveOverlay()
            routingManager = RoutingManager()
            safetyAlertManager = SafetyAlertManager(this)
            sharedPrefs = getSharedPreferences("roadwise_prefs", MODE_PRIVATE)
            setupMapGestures()

            allDetections.forEach { addHeatmapPoint(it) }

            PotholeRepository.fetchFromCloud(this) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && ::map.isInitialized) {
                        refreshMarkers()
                    }
                }
            }

            lifecycleScope.launch {
                while (isActive) {
                    val speedMs = locationOverlay.myLocationProvider?.lastKnownLocation?.speed ?: 0f
                    val speedKmh = (speedMs * 3.6).toInt()
                    
                    detectionManager.onSpeedUpdate(speedKmh)

                    if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh
                    withContext(Dispatchers.Main) {
                        val monitoringOn = binding.toggleMonitoring.isChecked
                        if (!monitoringOn) {
                            binding.monitoringStatus.setText(R.string.status_passive)
                            binding.monitoringStatus.setTextColor(Color.GRAY)
                        } else if (speedKmh < 5) {
                            binding.monitoringStatus.setText(if (speedKmh == 0) R.string.status_stationary else R.string.status_idle)
                            binding.monitoringStatus.setTextColor(Color.GRAY)
                        } else {
                            binding.monitoringStatus.setText(R.string.status_monitoring)
                            binding.monitoringStatus.setTextColor("#00FFC2".toColorInt())
                        }
                        binding.speedValue.text = speedKmh.toString()
                    }

                    if (sharedPrefs.getBoolean("pref_voice_alerts", true)) {
                        val location = locationOverlay.myLocation
                        val bearing = locationOverlay.myLocationProvider?.lastKnownLocation?.bearing ?: 0f
                        safetyAlertManager.checkHazards(location, bearing, speedKmh)
                    }

                    delay(1000)
                }
            }

            binding.speedValue.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Session Highlights")
                    .setMessage("Top Speed Today: $maxSpeedKmh km/h")
                    .setPositiveButton("Close", null)
                    .show()
                true
            }

            bumpDetector = BumpDetector(this, {
                val speedMs = locationOverlay.myLocationProvider?.lastKnownLocation?.speed ?: 0f
                (speedMs * 3.6).toInt()
            }) { type, intensity ->
                val lastKnown = locationOverlay.myLocationProvider?.lastKnownLocation
                val speedKmh = ((lastKnown?.speed ?: 0f) * 3.6).toInt()
                val loc = locationOverlay.myLocation ?: lastKnown?.let { GeoPoint(it) }
                detectionManager.onSensorDetection(type, intensity, speedKmh, loc)
            }
            
            val isMonitoringEnabled = sharedPrefs.getBoolean("pref_monitoring_enabled", SessionManager.isLoggedIn(this))
            binding.toggleMonitoring.isChecked = isMonitoringEnabled

            binding.toggleMonitoring.setOnCheckedChangeListener { _, isChecked ->
                sharedPrefs.edit { putBoolean("pref_monitoring_enabled", isChecked) }
                if (isChecked) {
                    bumpDetector.start()
                    startSwitchPulse()
                    if (!SessionManager.isLoggedIn(this)) {
                        Toast.makeText(this, "⚠️ Active Monitoring: Saved locally. Sign in to sync to cloud.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Monitoring Enabled", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    bumpDetector.stop()
                    stopSwitchPulse()
                    Toast.makeText(this, "Monitoring Disabled (Passive Alerts Only)", Toast.LENGTH_SHORT).show()
                }
            }

            if (isMonitoringEnabled) {
                bumpDetector.start()
                startSwitchPulse()
            }

            if (!allPermissionsGranted()) {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
            }

            binding.navDrive.setOnClickListener {
                if (isInNavigationMode) exitNavigation()
                updateNavUI(it)
                locationOverlay.enableFollowLocation()
            }

            binding.navAlerts.setOnClickListener {
                updateNavUI(it)
                if (SessionManager.isAdmin(this)) {
                    startActivity(Intent(this, OverviewActivity::class.java))
                } else {
                    startActivity(Intent(this, HistoryActivity::class.java))
                }
                applyFadeTransition()
            }

            binding.navSettings.setOnClickListener {
                updateNavUI(it)
                startActivity(Intent(this, SettingsActivity::class.java))
                applyFadeTransition()
            }

            binding.navAccount.setOnClickListener {
                if (SessionManager.isLoggedIn(this)) {
                    startActivity(Intent(this, AccountActivity::class.java))
                    applyFadeTransition()
                } else {
                    loginLauncher.launch(Intent(this, LoginActivity::class.java))
                }
            }

            binding.btnStopNav.setOnClickListener {
                exitNavigation()
            }

            binding.btnRecenter.setOnClickListener {
                val loc = locationOverlay.myLocation
                    ?: locationOverlay.myLocationProvider?.lastKnownLocation?.let { GeoPoint(it) }

                if (loc != null) {
                    locationOverlay.enableFollowLocation()
                    map.controller.animateTo(loc)
                    map.controller.setZoom(19.0)
                } else {
                    Toast.makeText(this, "Searching for GPS...", Toast.LENGTH_SHORT).show()
                }
            }

            setupSearchBar()
            updateNavForRole()

            if (SessionManager.isLoggedIn(this)) {
                SessionManager.checkAdminStatus(SessionManager.getUserEmail(this)) { currentStatus ->
                    if (currentStatus != SessionManager.isAdmin(this)) {
                        SessionManager.login(this, SessionManager.getUserEmail(this), SessionManager.getUserName(this), currentStatus)
                        updateNavForRole()
                    }
                }
            }

            handleFocusIntent(intent)

            if (sharedPrefs.getBoolean("pref_auto_start", false) && allPermissionsGranted()) {
                com.roadwise.utils.ActivityTransitionHelper.requestTransitions(this)
            }

        } catch (e: Exception) {
            Log.e("RoadWise", "FATAL STARTUP ERROR", e)
            Toast.makeText(this, "Startup error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyFadeTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.fade_in, R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }

    private fun updateNavForRole() {
        val isAdmin    = SessionManager.isAdmin(this)
        val isLoggedIn = SessionManager.isLoggedIn(this)

        if (isAdmin) {
            binding.navAlerts.setImageResource(R.drawable.ic_analytics)
            binding.navAlertsLabel.setText(R.string.nav_overview)
        } else {
            binding.navAlerts.setImageResource(R.drawable.ic_alerts)
            binding.navAlertsLabel.setText(R.string.nav_history)
        }

        binding.navAccountLabel.text = if (isLoggedIn) {
            SessionManager.getUserName(this).uppercase().take(8)
        } else {
            getString(R.string.account).uppercase()
        }
        
        updateNavUI(binding.navDrive)
    }

    private fun updateNavUI(active: View) {
        val faded = ContextCompat.getColor(this, R.color.text_med)
        val teal  = ContextCompat.getColor(this, R.color.emerald_neon)

        binding.navDrive.setColorFilter(faded)
        binding.navAlerts.setColorFilter(faded)
        binding.navSettings.setColorFilter(faded)
        binding.navAccount.setColorFilter(faded)
        binding.navDriveLabel.setTextColor(faded)
        binding.navAlertsLabel.setTextColor(faded)
        binding.navSettingsLabel.setTextColor(faded)
        binding.navAccountLabel.setTextColor(faded)

        when (active.id) {
            R.id.navDrive    -> { binding.navDrive.setColorFilter(teal);    binding.navDriveLabel.setTextColor(teal) }
            R.id.navAlerts   -> { binding.navAlerts.setColorFilter(teal);   binding.navAlertsLabel.setTextColor(teal) }
            R.id.navSettings -> { binding.navSettings.setColorFilter(teal); binding.navSettingsLabel.setTextColor(teal) }
            R.id.navAccount  -> { binding.navAccount.setColorFilter(teal);  binding.navAccountLabel.setTextColor(teal) }
        }
    }

    private fun setupSearchBar() {
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, mutableListOf()) {
            override fun getFilter(): android.widget.Filter {
                return object : android.widget.Filter() {
                    override fun performFiltering(constraint: CharSequence?) = FilterResults()
                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        if (count > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
                    }
                }
            }
        }
        binding.searchPlace.setAdapter(adapter)

        binding.searchPlace.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.length < 3) return
                searchJob?.cancel()
                searchJob = lifecycleScope.launch(Dispatchers.IO) {
                    delay(500)
                    withContext(Dispatchers.Main) { binding.searchProgress.visibility = View.VISIBLE }
                    val loc     = locationOverlay.myLocation
                    val results = routingManager.searchPlaces(query, loc?.latitude, loc?.longitude)
                    withContext(Dispatchers.Main) {
                        binding.searchProgress.visibility = View.GONE
                        searchResults = results
                        adapter.clear()
                        if (results.isEmpty()) adapter.add("No result found")
                        else adapter.addAll(results.map {
                            listOfNotNull(it.properties.name, it.properties.street, it.properties.city, it.properties.state, it.properties.country).joinToString(", ")
                        })
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        })

        binding.searchPlace.setOnItemClickListener { _, _, position, _ ->
            if (searchResults.isEmpty()) { binding.searchPlace.setText(""); return@setOnItemClickListener }
            val feature = searchResults.getOrNull(position) ?: return@setOnItemClickListener
            val dest    = GeoPoint(feature.geometry.coordinates[1], feature.geometry.coordinates[0])
            val start   = locationOverlay.myLocation
            if (start == null) { Toast.makeText(this, "Waiting for GPS...", Toast.LENGTH_SHORT).show(); return@setOnItemClickListener }
            map.overlays.remove(destinationMarker)
            destinationMarker = Marker(map).apply {
                this.position = dest
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = feature.properties.name ?: "Destination"
            }
            map.overlays.add(destinationMarker)
            map.controller.animateTo(dest)
            map.controller.setZoom(16.0)
            calculateAndDrawRoute(start, dest)
            binding.searchPlace.dismissDropDown()
            binding.searchPlace.clearFocus()
        }

        binding.searchPlace.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.searchPlace.windowToken, 0)
                true
            } else false
        }
    }

    private fun setupMapGestures() {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                val start = locationOverlay.myLocation
                if (start == null) { Toast.makeText(this@MainActivity, "Waiting for GPS location...", Toast.LENGTH_SHORT).show(); return false }
                map.overlays.remove(destinationMarker)
                destinationMarker = Marker(map).apply { position = p; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Destination" }
                map.overlays.add(destinationMarker)
                map.invalidate()
                calculateAndDrawRoute(start, p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean {
                val types = arrayOf("Pothole", "Speed Bump")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Select Hazard Type")
                    .setItems(types) { _, typeWhich ->
                        val type = if (typeWhich == 0) RoadFeature.POTHOLE else RoadFeature.SPEED_BUMP
                        val severities = arrayOf("Minor", "Moderate", "Severe", "Critical (Priority Repair)")
                        val intensities = arrayOf(0.8f, 1.5f, 2.5f, 3.5f)
                        
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Select Severity")
                            .setItems(severities) { _, sevWhich ->
                                val intensity = intensities[sevWhich]
                                simulateHazard(p, type, intensity)
                            }
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return true
            }
        }
        map.overlays.add(0, MapEventsOverlay(receiver))
    }

    private fun calculateAndDrawRoute(start: GeoPoint, end: GeoPoint) {
        Toast.makeText(this, "Calculating Safe Route...", Toast.LENGTH_SHORT).show()
        val allPotholes = PotholeRepository.getAllPotholes(this)
        val hazards     = allPotholes.filter { it.intensity > 0.8f }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val results = routingManager.getRoute(start, end, hazards)
                withContext(Dispatchers.Main) { drawRoutes(results) }
            } catch (e: Exception) {
                Log.e("RoadWise", "Analysis failed", e)
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Analysis failed.", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun drawRoutes(routes: List<RouteResult>) {
        currentRoutes = routes
        clearRouteFromMap()
        if (routes.isEmpty()) { Toast.makeText(this, "No route found.", Toast.LENGTH_SHORT).show(); return }
        
        val intent = Intent(this, RouteAnalysisActivity::class.java).apply {
            putParcelableArrayListExtra("ALL_ROUTES", ArrayList(routes))
        }
        analysisLauncher.launch(intent)
    }

    private fun clearRouteFromMap() {
        routeOverlays.forEach { map.overlays.remove(it) }
        routeOverlays.clear()
        destinationMarker?.let { map.overlays.remove(it); destinationMarker = null }
        map.invalidate()
    }

    private var isInNavigationMode = false

    private fun startNavigation(route: RouteResult) {
        isInNavigationMode = true
        binding.navPanel.visibility = View.VISIBLE
        binding.statsCard.visibility = View.GONE
        binding.searchCard.visibility = View.GONE
        binding.bottomNavCard.visibility = View.GONE
        
        val polyline = Polyline().apply {
            setPoints(route.points)
            outlinePaint.color = "#00E0FF".toColorInt()
            outlinePaint.strokeWidth = 22f
            outlinePaint.strokeCap = Paint.Cap.ROUND
        }
        routeOverlays.add(polyline)
        map.overlays.add(polyline)
        
        map.controller.animateTo(route.points.first(), 18.0, 1000L)
        
        val marker = Marker(map).apply {
            position = route.points.last()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_location_pin)?.apply {
                setTint("#FF3B30".toColorInt())
            }
            title = "Destination"
        }
        destinationMarker = marker
        map.overlays.add(marker)
        map.invalidate()
    }

    private fun exitNavigation() {
        isInNavigationMode = false
        binding.navPanel.visibility = View.GONE
        binding.statsCard.visibility = View.VISIBLE
        binding.searchCard.visibility = View.VISIBLE
        binding.bottomNavCard.visibility = View.VISIBLE
        clearRouteFromMap()
    }

    private fun initAdaptiveOverlay() {
        adaptiveOverlay = AdaptiveRoadOverlay(this, PotholeRepository)
        adaptiveOverlay.refresh()
        map.overlays.add(adaptiveOverlay)
    }

    private fun simulateHazard(location: GeoPoint, type: RoadFeature, intensity: Float) {
        val severity = when {
            intensity >= 2.5f -> Severity.HIGH
            intensity >= 1.5f -> Severity.MEDIUM
            else -> Severity.LOW
        }
        val data = PotholeData(location, type, intensity, severity, System.currentTimeMillis(), emptyList())
        PotholeRepository.savePothole(this, data)
        runOnUiThread {
            addHeatmapPoint(data); adaptiveOverlay.refresh(); map.invalidate()
            if (type == RoadFeature.POTHOLE) { verifiedPotholeCount++; binding.potholeCount.text = verifiedPotholeCount.toString() }
            Toast.makeText(this, "Simulated ${severity.name} ${type.name} added!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addHeatmapPoint(data: PotholeData) {
        if (!::map.isInitialized) return
        
        val baseColor = if (data.type == RoadFeature.SPEED_BUMP) "#00FFC2".toColorInt() else "#FFD700".toColorInt()

        val pinMarker = Marker(map)
        pinMarker.position = data.location
        pinMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        ContextCompat.getDrawable(this, R.drawable.ic_location_pin)?.let { icon -> 
            icon.setTint(baseColor)
            pinMarker.icon = icon 
        }
        pinMarker.title   = "${data.severity.name} ${data.type.name}"
        
        val sdf = SimpleDateFormat("MMM dd, yyyy · HH:mm:ss", Locale.getDefault())
        pinMarker.snippet = "Recorded: ${sdf.format(Date(data.timestamp))}\nIntensity: ${"%.1f".format(data.intensity)}g"
        
        map.overlays.add(pinMarker)
        map.invalidate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFocusIntent(intent)
    }

    private fun handleFocusIntent(intent: Intent?) {
        val lat = intent?.getDoubleExtra("FOCUS_LAT", 0.0) ?: 0.0
        val lon = intent?.getDoubleExtra("FOCUS_LON", 0.0) ?: 0.0
        if (lat != 0.0 && lon != 0.0) {
            val focusPoint = GeoPoint(lat, lon)
            binding.map.postDelayed({
                map.controller.animateTo(focusPoint)
                map.controller.setZoom(20.0)
            }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::map.isInitialized) {
            map.onResume()
            refreshMarkers()
        }
        
        val isMonitoringEnabled = sharedPrefs.getBoolean("pref_monitoring_enabled", true)
        if (binding.toggleMonitoring.isChecked != isMonitoringEnabled) {
            binding.toggleMonitoring.isChecked = isMonitoringEnabled
            if (isMonitoringEnabled) {
                bumpDetector.start()
                startSwitchPulse()
            } else {
                bumpDetector.stop()
                stopSwitchPulse()
            }
        }
        
        updateNavForRole()
    }

    private fun refreshMarkers() {
        if (!::map.isInitialized) return
        map.overlays.removeAll { it is Marker && it != locationOverlay && it != destinationMarker }
        val allDetections = PotholeRepository.getAllPotholes(this)
        allDetections.forEach { addHeatmapPoint(it) }
        verifiedPotholeCount = allDetections.count { it.type == RoadFeature.POTHOLE }
        binding.potholeCount.text = verifiedPotholeCount.toString()
        if (::adaptiveOverlay.isInitialized) adaptiveOverlay.refresh()
        map.invalidate()
    }

    override fun onPause() {
        super.onPause()
        if (::map.isInitialized) map.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && allPermissionsGranted()) {
            if (sharedPrefs.getBoolean("pref_auto_start", false)) {
                com.roadwise.utils.ActivityTransitionHelper.requestTransitions(this)
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startSwitchPulse() {
        if (switchPulseAnimator != null) return
        switchPulseAnimator = ValueAnimator.ofFloat(1.0f, 0.4f).apply {
            duration = 1000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                binding.toggleMonitoring.alpha = animator.animatedValue as Float
            }
            start()
        }
    }

    private fun stopSwitchPulse() {
        switchPulseAnimator?.cancel()
        switchPulseAnimator = null
        binding.toggleMonitoring.alpha = 1.0f
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::map.isInitialized) map.onDetach()
        if (::bumpDetector.isInitialized) bumpDetector.close()
        if (::safetyAlertManager.isInitialized) safetyAlertManager.shutdown()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
}

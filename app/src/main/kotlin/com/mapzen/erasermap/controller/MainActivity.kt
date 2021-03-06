package com.mapzen.erasermap.controller

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Point
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.preference.PreferenceManager
import android.support.v7.app.ActionBar
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import com.mapzen.android.MapView
import com.mapzen.android.MapzenMap
import com.mapzen.erasermap.CrashReportService
import com.mapzen.erasermap.EraserMapApplication
import com.mapzen.erasermap.R
import com.mapzen.erasermap.model.AndroidAppSettings
import com.mapzen.erasermap.model.ApiKeys
import com.mapzen.erasermap.model.AppSettings
import com.mapzen.erasermap.model.ConfidenceHandler
import com.mapzen.erasermap.model.MapzenLocation
import com.mapzen.erasermap.model.PermissionManager
import com.mapzen.erasermap.model.RouteManager
import com.mapzen.erasermap.model.TileHttpHandler
import com.mapzen.erasermap.presenter.MainPresenter
import com.mapzen.erasermap.util.AxisAlignedBoundingBox
import com.mapzen.erasermap.util.AxisAlignedBoundingBox.PointD
import com.mapzen.erasermap.util.NotificationBroadcastReceiver
import com.mapzen.erasermap.util.NotificationCreator
import com.mapzen.erasermap.view.CompassView
import com.mapzen.erasermap.view.DirectionListAdapter
import com.mapzen.erasermap.view.DirectionListView
import com.mapzen.erasermap.view.DistanceView
import com.mapzen.erasermap.view.MuteView
import com.mapzen.erasermap.view.RouteModeView
import com.mapzen.erasermap.view.RoutePreviewView
import com.mapzen.erasermap.view.SearchResultsAdapter
import com.mapzen.erasermap.view.SearchResultsView
import com.mapzen.erasermap.view.SettingsActivity
import com.mapzen.erasermap.view.Speaker
import com.mapzen.erasermap.view.VoiceNavigationController
import com.mapzen.pelias.Pelias
import com.mapzen.pelias.SavedSearch
import com.mapzen.pelias.SimpleFeature
import com.mapzen.pelias.gson.Feature
import com.mapzen.pelias.gson.Geometry
import com.mapzen.pelias.gson.Properties
import com.mapzen.pelias.gson.Result
import com.mapzen.pelias.widget.AutoCompleteAdapter
import com.mapzen.pelias.widget.AutoCompleteItem
import com.mapzen.pelias.widget.AutoCompleteListView
import com.mapzen.pelias.widget.PeliasSearchView
import com.mapzen.tangram.LngLat
import com.mapzen.tangram.TouchInput
import com.mapzen.valhalla.Route
import com.mapzen.valhalla.RouteCallback
import com.mapzen.valhalla.Router
import retrofit.Callback
import retrofit.RetrofitError
import retrofit.client.Response
import java.math.RoundingMode
import java.text.DecimalFormat
import java.util.ArrayList
import javax.inject.Inject

class MainActivity : AppCompatActivity(), MainViewController, RouteCallback,
        SearchViewController.OnSearchResultSelectedListener {

    companion object {
        @JvmStatic val MAP_DATA_PROP_SEARCHINDEX = "searchIndex"
        @JvmStatic val MAP_DATA_PROP_ID = "id"
        @JvmStatic val MAP_DATA_PROP_NAME = "name"
        @JvmStatic val DIRECTION_LIST_ANIMATION_DURATION = 300L
        @JvmStatic val PERMISSIONS_REQUEST: Int = 1
        @JvmStatic val SCENE_CAMERA = "cameras"
        @JvmStatic val SCENE_CAMERA_ISOMETRIC = "{isometric: {type: isometric}}"
        @JvmStatic val SCENE_CAMERA_PERSPECTIVE = "{perspective: {type: perspective}}"
    }

    @Inject lateinit var savedSearch: SavedSearch
    @Inject lateinit var presenter: MainPresenter
    @Inject lateinit var crashReportService: CrashReportService
    @Inject lateinit var routeManager: RouteManager
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var tileHttpHandler: TileHttpHandler
    @Inject lateinit var mapzenLocation: MapzenLocation
    @Inject lateinit var pelias: Pelias
    @Inject lateinit var speaker: Speaker
    @Inject lateinit var permissionManager: PermissionManager
    @Inject lateinit var apiKeys: ApiKeys

    lateinit var app: EraserMapApplication
    var mapzenMap : MapzenMap? = null
    var autoCompleteAdapter: AutoCompleteAdapter? = null
    var optionsMenu: Menu? = null
    var poiTapPoint: FloatArray? = null
    var poiTapName: String? = null
    var voiceNavigationController: VoiceNavigationController? = null
    var notificationCreator: NotificationCreator? = null
    lateinit var confidenceHandler: ConfidenceHandler
    var enableLocation: Boolean = false

    // activity_main
    val mapView: MapView by lazy { findViewById(R.id.map) as MapView }
    val compass: CompassView by lazy { findViewById(R.id.compass_view) as CompassView }
    val searchController: SearchViewController by lazy { findViewById(R.id.search_results) as SearchResultsView }

    // view_route_preview
    val routePreviewView: RoutePreviewView by lazy { findViewById(R.id.route_preview) as RoutePreviewView }
    val reverseButton: ImageButton by lazy { findViewById(R.id.route_reverse) as ImageButton }
    val routePreviewDistanceTimeLayout: LinearLayout by lazy { findViewById(R.id.route_preview_distance_time_view) as LinearLayout }
    val viewListButton: Button by lazy { findViewById(R.id.view_list) as Button }
    val startNavigationButton: Button by lazy { findViewById(R.id.start_navigation) as Button }
    val byCar: RadioButton by lazy { findViewById(R.id.by_car) as RadioButton }
    val byBike: RadioButton by lazy { findViewById(R.id.by_bike) as RadioButton }
    val byFoot: RadioButton by lazy { findViewById(R.id.by_foot) as RadioButton }
    val routePreviewCompass: CompassView by lazy { findViewById(R.id.route_preview_compass_view) as CompassView }
    val routeTopContainer: RelativeLayout by lazy { routePreviewView.findViewById(R.id.main_content) as RelativeLayout }
    val routeBtmContainer: LinearLayout by lazy { routePreviewView.findViewById(R.id.bottom_content) as LinearLayout }
    val distanceView: DistanceView by lazy { routePreviewView.findViewById(R.id.destination_distance) as DistanceView }
    val destinationNameTextView: TextView by lazy { findViewById(R.id.destination_name) as TextView }
    val previewDirectionListView: DirectionListView by lazy { routePreviewView.findViewById(R.id.list_view) as DirectionListView }
    val previewToggleBtn: View by lazy { routePreviewView.findViewById(R.id.map_list_toggle) }
    val balancerView: View by lazy { routePreviewView.findViewById(R.id.balancer) }

    // view_route_mode
    val routeModeView: RouteModeView by lazy { findViewById(R.id.route_mode) as RouteModeView }
    val routeModeCompass: CompassView by lazy { findViewById(R.id.route_mode_compass_view) as CompassView }
    val muteView: MuteView by lazy { findViewById(R.id.route_mode_mute_view) as MuteView }

    var submitQueryOnMenuCreate: String? = null

    override public fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as EraserMapApplication
        app.component().inject(this)
        initCrashReportService()
        setContentView(R.layout.activity_main)
        presenter.mainViewController = this
        initMapzenMap()
        initVoiceNavigationController() // must initialize this before calling initMute
        initMute()
        initCompass()
        initReverseButton()
        initMapRotateListener()
        initConfidenceHandler()
        permissionManager.activity = this
        if (permissionManager.permissionsRequired()) {
            permissionManager.requestPermissions()
        } else {
            permissionManager.grantPermissions()
        }
        presenter.onRestoreViewState()
        supportActionBar?.setDisplayShowTitleEnabled(false)
        settings.initTangramDebugFlags()
        settings.initSearchResultVersion(this, savedSearch)
        initVoiceNavigationController()
        initNotificationCreator()
    }

    override fun onNewIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(NotificationCreator.EXIT_NAVIGATION, false) as Boolean) {
            exitNavigation()
            if((intent?.getBooleanExtra(NotificationBroadcastReceiver.VISIBILITY, false)
                    as Boolean).not()) {
                moveTaskToBack(true)
            }
        } else {
            presenter.onIntentQueryReceived(intent?.data?.query)
        }
    }

    private fun initMapRotateListener() {
        mapzenMap?.setRotateResponder({ x, y, rotation -> presenter.onMapMotionEvent() })
    }

    override fun rotateCompass() {
        val radians: Float = mapzenMap?.rotation as Float
        val degrees = Math.toDegrees(radians.toDouble()).toFloat()
        compass.rotation = degrees
        routePreviewCompass.rotation = degrees
        routeModeCompass.rotation = degrees
    }

    override public fun onStart() {
        super.onStart()
        savedSearch.deserialize(PreferenceManager.getDefaultSharedPreferences(this)
                .getString(SavedSearch.TAG, null))
    }

    override public fun onResume() {
        super.onResume()
        if (!permissionManager.permissionsRequired()) {
            permissionManager.grantPermissions()
        }
        presenter.onResume()
        app?.onActivityResume()
        autoCompleteAdapter?.clear()
        autoCompleteAdapter?.notifyDataSetChanged()
        invalidateOptionsMenu()

        if (enableLocation) {
            enableLocation = false
            checkPermissionAndEnableLocation()
        }
    }

    override public fun onPause() {
        super.onPause()
        app?.onActivityPause()
        if (mapzenMap?.isMyLocationEnabled != null && mapzenMap?.isMyLocationEnabled as Boolean
                && !presenter.routingEnabled) {
            enableLocation = true
            mapzenMap?.isMyLocationEnabled = false
        }
    }

    override public fun onStop() {
        super.onStop()
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(SavedSearch.TAG, savedSearch?.serialize())
                .commit()
    }

    override public fun onDestroy() {
        super.onDestroy()
        saveCurrentSearchTerm()
        mapzenMap?.isMyLocationEnabled = false
        killNotifications()
        voiceNavigationController?.shutdown()
    }

    private fun initMapzenMap() {
        mapView.getMapAsync(apiKeys.tilesKey, {
            this.mapzenMap = it
            configureMapzenMap()
            presenter.configureMapzenMap()
            presenter.onIntentQueryReceived(intent?.data?.query)
        })
    }

    private fun configureMapzenMap() {
        mapzenMap?.setLongPressResponder({
            x, y ->
            confidenceHandler.longPressed = true
            presenter.onReverseGeoRequested(x, y)
        })
        mapzenMap?.tapResponder = object: TouchInput.TapResponder {
            override fun onSingleTapUp(x: Float, y: Float): Boolean = false
            override fun onSingleTapConfirmed(x: Float, y: Float): Boolean {
                confidenceHandler.longPressed = false
                var coords = mapzenMap?.coordinatesAtScreenPosition(x.toDouble(), y.toDouble())
                presenter?.reverseGeoLngLat = coords
                poiTapPoint = floatArrayOf(x, y)
                return true
            }
        }
        mapzenMap?.setDoubleTapResponder({ x, y ->
            confidenceHandler.longPressed = false
            val tappedPos = mapzenMap?.coordinatesAtScreenPosition(x.toDouble(), y.toDouble())
            val currentPos = mapzenMap?.position
            if (tappedPos != null && currentPos != null) {
                mapzenMap?.setZoom((mapzenMap?.zoom as Float) + 1.0f, 500)
                val lngLat = LngLat(0.5f * (tappedPos.longitude + currentPos.longitude),
                        0.5f * (tappedPos.latitude + currentPos.latitude));
                mapzenMap?.setPosition(lngLat, 500);
            }
            true;
        })
        mapzenMap?.setFeaturePickListener({
            properties, positionX, positionY ->
            confidenceHandler.longPressed = false
            // Reassign tapPoint to center of the feature tapped
            // Also used in placing the pin
            poiTapPoint = floatArrayOf(positionX, positionY)
            if (properties.contains(MAP_DATA_PROP_NAME)) {
                poiTapName = properties[MAP_DATA_PROP_NAME];
            }
            if (properties.contains(MAP_DATA_PROP_SEARCHINDEX)) {
                val searchIndex = properties[MAP_DATA_PROP_SEARCHINDEX]!!.toInt()
                presenter.onSearchResultTapped(searchIndex)
            } else {
                presenter.onReverseGeoRequested(poiTapPoint?.get(0)?.toFloat(),
                            poiTapPoint?.get(0)?.toFloat())
            }
        })
        checkPermissionAndEnableLocation()
        mapzenMap?.setFindMeOnClickListener {
            if (permissionManager.permissionsGranted()) {
                presenter.onFindMeButtonClick()
            } else {
                permissionManager.showPermissionRequired()
            }
        }
        mapzenMap?.mapController?.setHttpHandler(tileHttpHandler)
        mapzenLocation.mapzenMap = mapzenMap
        routeModeView.mapzenMap = mapzenMap
        settings.mapzenMap = mapzenMap
    }

    private fun updateMute() {
        muteView.setMuted(voiceNavigationController?.isMuted() != true)
    }

    private fun initMute() {
        updateMute()
        muteView.setOnClickListener({
            presenter.onMuteClick()
        })
    }

    private fun initCompass() {
        compass.setOnClickListener({
            presenter.onCompassClick()
        })
        routePreviewCompass.setOnClickListener({
            presenter.onCompassClick()
        })
        routeModeCompass.setOnClickListener({
            presenter.onCompassClick()
        })
    }

    private fun initCrashReportService() {
        crashReportService.initAndStartSession(this)
    }

    private fun initVoiceNavigationController() {
        voiceNavigationController = VoiceNavigationController(this, speaker)
    }

    private fun initNotificationCreator() {
        notificationCreator = NotificationCreator(this)
    }

    private fun initConfidenceHandler() {
        confidenceHandler = ConfidenceHandler(presenter)
    }

    override fun centerMapOnLocation(lngLat: LngLat, zoom: Float) {
        mapzenMap?.position = lngLat
        mapzenMap?.zoom = zoom
        centerMap(lngLat)
    }

    override fun setMapTilt(radians: Float) {
        mapzenMap?.tilt = radians
    }

    override fun toggleMute() {
        val muted = (voiceNavigationController?.isMuted() == true)
        if (muted) {
            muteView.setMuted(true)
            voiceNavigationController?.unmute()
        } else {
            muteView.setMuted(false)
            voiceNavigationController?.mute()
        }
    }

    override fun setMapRotation(radians: Float) {
        mapzenMap?.setRotation(radians, 1000)
        compass.reset()
        routePreviewCompass.reset()
        routeModeCompass.reset()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        optionsMenu = menu
        initSearchView()
        return true
    }

    private fun initSearchView() {
        val searchView = PeliasSearchView(this)
        val listView = findViewById(R.id.auto_complete) as AutoCompleteListView
        val emptyView = findViewById(android.R.id.empty) as View
        val locationProvider = presenter.getPeliasLocationProvider()
        val apiKeys = apiKeys
        val callback = PeliasCallback()

        addSearchViewToActionBar(searchView)
        searchController.mainController = this
        searchController.initSearchView(searchView, listView, emptyView, presenter, locationProvider, apiKeys, callback)
        if (submitQueryOnMenuCreate != null) {
            searchView.setQuery(submitQueryOnMenuCreate, true)
            submitQueryOnMenuCreate = null
        }
    }

    private fun addSearchViewToActionBar(searchView: PeliasSearchView) {
        supportActionBar?.setCustomView(searchView, ActionBar.LayoutParams(
                ActionBar.LayoutParams.MATCH_PARENT, ActionBar.LayoutParams.WRAP_CONTENT))
        val displayOptions = supportActionBar?.displayOptions ?: 0
        supportActionBar?.displayOptions = displayOptions or ActionBar.DISPLAY_SHOW_CUSTOM
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val cacheSearches = prefs.getBoolean(AndroidAppSettings.KEY_CACHE_SEARCH_HISTORY, true)
        searchController.searchView?.setCacheSearchResults(cacheSearches)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        when (id) {
            R.id.action_settings -> { onActionSettings(); return true }
            R.id.action_view_all -> { onActionViewAll(); return true }
        }

        return super.onOptionsItemSelected(item)
    }

    private fun onActionSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun onActionViewAll() {
        presenter.onViewAllSearchResults()
    }

    override fun showAllSearchResults(features: List<Feature>?) {
        if (features == null) {
            return
        }

        if (presenter.resultListVisible) {
            onCloseAllSearchResults()
            searchController.enableSearch()
        } else {
            saveCurrentSearchTerm()
            presenter.resultListVisible = true
            optionsMenu?.findItem(R.id.action_view_all)?.setIcon(R.drawable.ic_map)

            val simpleFeatures: ArrayList<AutoCompleteItem> = ArrayList()
            for (feature in features) {
                simpleFeatures.add(AutoCompleteItem(SimpleFeature.fromFeature(feature)))
            }
            searchController.searchView?.disableAutoKeyboardShow()
            searchController.searchView?.disableAutoComplete()
            searchController.searchView?.onActionViewExpanded()
            searchController.searchView?.setQuery(presenter.currentSearchTerm, false)
            val autoCompleteAdapter = searchController.autoCompleteListView?.adapter as AutoCompleteAdapter
            autoCompleteAdapter.clear();
            autoCompleteAdapter.addAll(simpleFeatures);
            autoCompleteAdapter.notifyDataSetChanged();
            searchController.autoCompleteListView?.setOnItemClickListener { parent, view, position, id ->
                        (findViewById(R.id.search_results) as SearchResultsView).setCurrentItem(position)
                        onCloseAllSearchResults()

            }
            searchController.disableSearch()
        }
    }

    override fun onCloseAllSearchResults() {
        searchController.autoCompleteListView?.onItemClickListener = searchController.searchView?.OnItemClickHandler()?.invoke()
        presenter.resultListVisible = false
        optionsMenu?.findItem(R.id.action_view_all)?.setIcon(R.drawable.ic_list)
        searchController.searchView?.enableAutoKeyboardShow()
        searchController.searchView?.onActionViewCollapsed()
        searchController.searchView?.isIconified = false
        searchController.searchView?.clearFocus()
        searchController.searchView?.disableAutoComplete()
        searchController.searchView?.setQuery(presenter.currentSearchTerm, false)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode > 0) {
            searchController.setCurrentItem(resultCode - 1)
        }
    }

    private fun saveCurrentSearchTerm() {
        presenter.currentSearchTerm = searchController.searchView?.query.toString()
    }

    inner class PeliasCallback : Callback<Result> {
        private val TAG: String = "PeliasCallback"

        override fun success(result: Result?, response: Response?) {
            presenter.reverseGeoLngLat = null
            presenter.onSearchResultsAvailable(result)
            optionsMenu?.findItem(R.id.action_view_all)?.setIcon(R.drawable.ic_list)
        }

        override fun failure(error: RetrofitError?) {
            hideProgress()
            Log.e(TAG, "Error fetching search results: " + error?.message)
            Toast.makeText(this@MainActivity, "Error fetching search results",
                    Toast.LENGTH_LONG).show()
        }
    }

    inner class ReversePeliasCallback : Callback<Result> {
        private val TAG: String = "ReversePeliasCallback"

        override fun success(result: Result?, response: Response?) {
            presenter.onReverseGeocodeResultsAvailable(result)
        }

        override fun failure(error: RetrofitError?) {
            hideProgress()
            Log.e(TAG, "Error Reverse Geolocating: " + error?.message)
        }
    }

    inner class PlaceCallback : Callback<Result> {
        private val TAG: String = "PlaceCallback"

        override fun success(result: Result?, response: Response?) {
            presenter.onPlaceSearchResultsAvailable(result)
        }

        override fun failure(error: RetrofitError?) {
            hideProgress()
            Log.e(TAG, "Error fetching place search results: " + error?.message)
        }
    }

    override fun showSearchResults(features: List<Feature>?) {
        if (features == null) {
            return
        }

        hideReverseGeolocateResult()
        showSearchResultsView(features)
        addSearchResultsToMap(features, 0)
        layoutAttributionAboveSearchResults(features)
        layoutFindMeAboveSearchResults(features)
        toggleShowDebugSettings()
    }

    private fun showSearchResultsView(features: List<Feature>) {
        searchController.setSearchResultsAdapter(SearchResultsAdapter(this, features, confidenceHandler,
                permissionManager))
        searchController.showSearchResults()
        searchController.onSearchResultsSelectedListener = this
    }

    private fun baseAttributionParams(): RelativeLayout.LayoutParams {
        val layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT)
        val margin = resources.getDimensionPixelSize(R.dimen.padding_vertical)
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        layoutParams.leftMargin = margin
        return layoutParams
    }

    private fun layoutAttributionAlignBottom() {
        val layoutParams = baseAttributionParams()
        val margin = resources.getDimensionPixelSize(R.dimen.padding_vertical)
        layoutParams.bottomMargin = margin
        mapView.attribution.layoutParams = layoutParams
    }

    private fun baseFindMeParams(): RelativeLayout.LayoutParams {
        val scale = resources.displayMetrics.density;
        val size = (44 * scale).toInt()
        val layoutParams = RelativeLayout.LayoutParams(size, size)
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
        val margin = resources.getDimensionPixelSize(R.dimen.padding_vertical)
        layoutParams.rightMargin = margin
        return layoutParams
    }

    private fun layoutFindMeAlignBottom() {
        val layoutParams = baseFindMeParams()
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        val margin = resources.getDimensionPixelSize(R.dimen.padding_vertical)
        layoutParams.bottomMargin = margin
        mapView.findMe.layoutParams = layoutParams
    }

    private fun layoutAttributionAboveSearchResults(features: List<Feature>) {
        if (features.count() == 0) return
        val layoutParams = baseAttributionParams()
        val bottomMargin = resources.getDimensionPixelSize(R.dimen.padding_vertical_big)
        val searchHeight = resources.getDimensionPixelSize(R.dimen.search_results_pager_height)
        val padding = resources.getDimensionPixelSize(R.dimen.padding_vertical_default)
        val indicator = findViewById(R.id.indicator)
        if (features.count() > 1) {
            indicator?.addOnLayoutChangeListener({ view, left, top, right, bottom, oldLeft, oldTop,
                                                   oldRight, oldBottom ->
                val indicatorHeight = bottom - top
                layoutParams.bottomMargin = searchHeight + indicatorHeight + bottomMargin - padding
                mapView.attribution.layoutParams = layoutParams
            })
        } else {
            layoutParams.bottomMargin = searchHeight + bottomMargin - padding
            mapView.attribution.layoutParams = layoutParams
        }
    }

    private fun layoutAttributionAboveOptions() {
        val layoutParams = baseAttributionParams()
        val optionsView = findViewById(R.id.options)
        optionsView?.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop,
                                                 oldRight, oldBottom ->
            val optionsHeight = bottom - top
            val padding = resources.getDimensionPixelSize(R.dimen.padding_vertical_default)
            layoutParams.bottomMargin = optionsHeight + padding
            mapView.attribution.layoutParams = layoutParams
        }
    }

    private fun layoutFindMeAboveSearchResults(features: List<Feature>) {
        if (features.count() == 0) return
        val layoutParams = baseFindMeParams()
        val bottomMargin = resources.getDimensionPixelSize(R.dimen.padding_vertical_big)
        val searchHeight = resources.getDimensionPixelSize(R.dimen.search_results_pager_height)
        val padding = resources.getDimensionPixelSize(R.dimen.padding_vertical_default)
        layoutParams.bottomMargin = searchHeight + bottomMargin - padding
        mapView.findMe.layoutParams = layoutParams
    }

    private fun layoutFindMeAboveOptions() {
        val layoutParams = baseFindMeParams()
        val optionsView = findViewById(R.id.options)
        optionsView?.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop,
                                                 oldRight, oldBottom ->
            val optionsHeight = bottom - top
            val padding = resources.getDimensionPixelSize(R.dimen.padding_vertical_default)
            layoutParams.bottomMargin = optionsHeight + padding
            mapView.findMe.layoutParams = layoutParams
        }
    }

    /**
     * If the current query in search view is equal to special query, toggle shared preferences
     * to show or hide debug settings in settings fragment
     */
    private fun toggleShowDebugSettings() {
        if (!AndroidAppSettings.SHOW_DEBUG_SETTINGS_QUERY.equals(searchController.searchView?.query.toString())) {
            return;
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val editor = preferences.edit()
        val prev = preferences.getBoolean(AndroidAppSettings.KEY_SHOW_DEBUG_SETTINGS, false)
        editor.putBoolean(AndroidAppSettings.KEY_SHOW_DEBUG_SETTINGS, !prev)
        editor.commit()

        val status = resources.getString(if (prev) R.string.disabled else R.string.enabled)
        val debugToastTitle = resources.getString(R.string.debug_settings_toast_title, status)
        Toast.makeText(this, debugToastTitle, Toast.LENGTH_SHORT).show();
    }

    override fun showReverseGeocodeFeature(features: List<Feature>?) {
        if (features == null) {
            return
        }

        hideSearchResults()
        layoutAttributionAboveSearchResults(features)
        layoutFindMeAboveSearchResults(features)

        val lngLat: LngLat?
        if (poiTapPoint != null) {
            val x = poiTapPoint!![0].toDouble()
            val y = poiTapPoint!![1].toDouble()
            lngLat = mapzenMap?.coordinatesAtScreenPosition(x, y)

            // Fallback for a failed Pelias Place Callback
            overridePlaceFeature(features.get(0))
        } else {
            lngLat = presenter.reverseGeoLngLat
        }

        showPlaceSearchFeature(features)

        mapzenMap?.clearDroppedPins()
        mapzenMap?.drawDroppedPin(lngLat)
    }

    override fun drawTappedPoiPin() {
        hideSearchResultsView()
        layoutAttributionAlignBottom()
        layoutFindMeAlignBottom()

        var lngLat: LngLat? = null

        val pointX = poiTapPoint?.get(0)?.toDouble()
        val pointY = poiTapPoint?.get(1)?.toDouble()
        if (pointX != null && pointY != null) {
            lngLat = mapzenMap?.coordinatesAtScreenPosition(pointX, pointY)
        }

        mapzenMap?.clearDroppedPins()
        mapzenMap?.drawDroppedPin(lngLat)
    }

    override fun showPlaceSearchFeature(features: List<Feature>) {
        searchController.setSearchResultsAdapter(SearchResultsAdapter(this, features.subList(0, 1),
                confidenceHandler, permissionManager))
        searchController.showSearchResults()
        searchController.onSearchResultsSelectedListener = this
    }

    override fun addSearchResultsToMap(features: List<Feature>?, activeIndex: Int) {
        if (features == null) {
            return
        }

        centerOnCurrentFeature(features)

        mapzenMap?.clearSearchResults()
        val points: ArrayList<LngLat> = ArrayList()
        for (feature in features) {
            val simpleFeature = SimpleFeature.fromFeature(feature)
            val lngLat = LngLat(simpleFeature.lng(), simpleFeature.lat())
            points.add(lngLat)
        }
        mapzenMap?.drawSearchResults(points, activeIndex)
    }

    override fun centerOnCurrentFeature(features: List<Feature>?) {
        if (features == null) {
            return
        }

        centerOnFeature(features, searchController.getCurrentItem())
    }

    override fun centerOnFeature(features: List<Feature>?, position: Int) {
        if (features == null) {
            return
        }

        if(features.size > 0) {
            searchController.setCurrentItem(position)
            val feature = SimpleFeature.fromFeature(features[position])
            Handler().postDelayed({
                mapzenMap?.setPosition(LngLat(feature.lng(), feature.lat()), 1000)
                mapzenMap?.zoom = MainPresenter.DEFAULT_ZOOM
            }, 100)
        }
    }

    override fun placeSearch(gid: String) {
        pelias.setLocationProvider(presenter.getPeliasLocationProvider())
        pelias.place(gid, (PlaceCallback()))
    }

    override fun emptyPlaceSearch() {
        if (poiTapPoint != null) {
            presenter.onReverseGeoRequested(poiTapPoint?.get(0)?.toFloat(), poiTapPoint?.get(1)?.toFloat())
        }
    }

    override fun reverseGeolocate(screenX: Float, screenY: Float) {
        pelias.setLocationProvider(presenter.getPeliasLocationProvider())
        val coords = mapzenMap?.coordinatesAtScreenPosition(screenX.toDouble(), screenY.toDouble())
        presenter.reverseGeoLngLat = coords
        presenter.currentFeature = getGenericLocationFeature(coords?.latitude as Double,
                coords?.longitude as Double)
        pelias.reverse(coords?.latitude as Double, coords?.longitude as Double,
                ReversePeliasCallback())
    }

    override fun hideReverseGeolocateResult() {
        mapzenMap?.clearDroppedPins()
    }

    override fun hideSearchResults() {
        hideSearchResultsView()
        layoutAttributionAlignBottom()
        layoutFindMeAlignBottom()
        mapzenMap?.clearSearchResults()
    }

    private fun hideSearchResultsView() {
        searchController.hideSearchResults()
    }

    override fun showProgress() {
        findViewById(R.id.progress)?.visibility = View.VISIBLE
    }

    override fun hideProgress() {
        findViewById(R.id.progress)?.visibility = View.GONE
    }

    override fun onSearchResultSelected(position: Int) {
        presenter.onSearchResultSelected(position)
    }

    override fun showActionViewAll() {
        optionsMenu?.findItem(R.id.action_view_all)?.setVisible(true)
    }

    override fun hideActionViewAll() {
        optionsMenu?.findItem(R.id.action_view_all)?.setVisible(false)
    }

    override fun collapseSearchView() {
        presenter.onCollapseSearchView()
    }

    override fun expandSearchView() {
        presenter.onExpandSearchView()
    }

    override fun clearQuery() {
        searchController.searchView?.setQuery("", false)
    }

    override fun hideSettingsBtn() {
        Handler().postDelayed( { optionsMenu?.findItem(R.id.action_settings)?.isVisible = false },
                100)
    }

    override fun showSettingsBtn() {
        optionsMenu?.findItem(R.id.action_settings)?.setVisible(true)
    }

    private fun centerMap(lngLat: LngLat) {
        mapzenMap?.position = lngLat
    }

    override fun showRoutePreview(location: Location, feature: Feature) {
        centerMap(LngLat(location.longitude, location.latitude));
        layoutAttributionAboveOptions()
        layoutFindMeAboveOptions()

        routeManager.origin = location

        if (location.hasBearing()) {
            routeManager.bearing = location.bearing
        } else {
            routeManager.bearing = null
        }

        if (!confidenceHandler.useRawLatLng(feature.properties.confidence)) {
            routePreviewView.destination = SimpleFeature.fromFeature(feature)
            routeManager.destination = feature
        } else {
            val rawFeature = generateRawFeature()
            routePreviewView.destination = SimpleFeature.fromFeature(rawFeature)
            routeManager.destination = rawFeature
        }
        route()
    }

    private fun generateRawFeature(): Feature {
        var rawFeature: Feature = Feature()
        rawFeature.geometry = Geometry()
        val coords = ArrayList<Double>()
        coords.add(presenter?.reverseGeoLngLat?.longitude as Double)
        coords.add(presenter?.reverseGeoLngLat?.latitude as Double)
        rawFeature.geometry.coordinates = coords
        val properties = Properties()
        val formatter = DecimalFormat(".####")
        formatter.roundingMode = RoundingMode.HALF_UP
        val lng = formatter.format(presenter?.reverseGeoLngLat?.longitude as Double)
        val lat = formatter.format(presenter?.reverseGeoLngLat?.latitude  as Double)
        properties.name = "$lng, $lat"
        rawFeature.properties = properties
        return rawFeature
    }

    override fun drawRoute(route: Route) {
        routeModeView.drawRoute(route)
    }

    override fun clearRoute() {
        routeModeView.clearRoute()
    }

    override fun success(route: Route) {
        routeManager.route = route
        routePreviewView.route = route
        runOnUiThread ({
            if (routeModeView.visibility != View.VISIBLE) {
                supportActionBar?.hide()
                routePreviewView.visibility = View.VISIBLE
                routePreviewDistanceTimeLayout.visibility = View.VISIBLE
                zoomToShowRoute(route.getGeometry().toTypedArray())
            }
        })
        updateRoutePreview()
        routeModeView.drawRoute(route)
        routePreviewView.enableStartNavigation()
        hideProgress()
    }

    private fun zoomToShowRoute(route: Array<Location>) {

        // Make sure we have some points to work with
        if (route.isEmpty()) {
            return
        }

        mapzenMap?.rotation = 0f
        mapzenMap?.tilt = 0f

        // Determine the smallest axis-aligned box that contains the route longitude and latitude
        val start = route.first()
        val finish = route.last()
        var routeBounds = AxisAlignedBoundingBox()
        routeBounds.center = PointD(start.longitude, start.latitude)

        for (p in route) {
            routeBounds.expandTo(p.longitude, p.latitude)
        }

        // Add some padding to the box
        val padding = 0.35
        routeBounds.width = (1.0 + padding) * routeBounds.width
        routeBounds.height = (1.0 + padding) * routeBounds.height

        // Determine the bounds of the current view in longitude and latitude
        val screen = Point()
        windowManager.defaultDisplay.getSize(screen)
        // Take half of the y dimension to allow space for other UI elements
        val viewMin = mapzenMap?.coordinatesAtScreenPosition(0.0, screen.y.toDouble() * 0.25) ?: LngLat()
        val viewMax = mapzenMap?.coordinatesAtScreenPosition(screen.x.toDouble(), screen.y.toDouble() * 0.75) ?: LngLat()

        // Determine the amount of re-scaling needed to view the route
        val scaleX = routeBounds.width / Math.abs(viewMax.longitude - viewMin.longitude)
        val scaleY = routeBounds.height / Math.abs(viewMax.latitude - viewMin.latitude)
        val zoomDelta = -Math.log(Math.max(scaleX, scaleY)) / Math.log(2.0)

        // Update map position and zoom
        var map = mapzenMap
        if (map != null) {
            val z = map.zoom + zoomDelta.toFloat()
            map.zoom = z
            if (map.zoom == z) {
                // If the new zoom would go beyond the bounds of the earth, the value
                // won't be set - so we want to make sure that it changed before moving
                // the position.
                map.position = LngLat(routeBounds.center.x, routeBounds.center.y)
            }
        }

        hideRoutePins()
        showRoutePins(LngLat(start.longitude, start.latitude),
                LngLat(finish.longitude, finish.latitude))
    }

    private fun showRoutePins(start: LngLat, end: LngLat) {
        mapzenMap?.drawRoutePins(start, end)
    }

    private fun handleRouteFailure() {
        hideRoutePins()
        routeModeView.hideRouteLine()

        val origin = routeManager.origin
        val destination = routeManager.destination
        if (origin is Location && destination is Feature) {
            val destinationFeature = SimpleFeature.fromFeature(destination)
            val start = LngLat(origin.longitude, origin.latitude)
            val end = LngLat(destinationFeature.lng(), destinationFeature.lat())
            showRoutePins(start, end)

            val startLocation = origin
            val endLocation = Location(origin)
            endLocation.longitude = end.longitude
            endLocation.latitude = end.latitude
            zoomToShowRoute(arrayOf(startLocation, endLocation))
        }
    }

    override fun failure(statusCode: Int) {
        runOnUiThread ({
            if (routeModeView.visibility != View.VISIBLE) {
                supportActionBar?.hide()
                routePreviewView.visibility = View.VISIBLE
                routePreviewDistanceTimeLayout.visibility = View.INVISIBLE
                handleRouteFailure()
            }
        })
        updateRoutePreview()
        hideProgress()
        Toast.makeText(this@MainActivity, "No route found", Toast.LENGTH_LONG).show()
        routePreviewView.disableStartNavigation()
    }

    override fun hideRoutePreview() {
        if((findViewById(R.id.route_mode) as RouteModeView).visibility != View.VISIBLE) {
            supportActionBar?.show()
            routeManager.reverse = false
            findViewById(R.id.route_preview)?.visibility = View.GONE
            hideRoutePins()
            val features = arrayListOf(presenter.currentFeature) as List<Feature>
            layoutAttributionAboveSearchResults(features)
            layoutFindMeAboveSearchResults(features)
        }
    }

    private fun route() {
        showProgress()
        routeManager.fetchRoute(this)
    }

    private fun updateRoutePreview() {
        byCar.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                routeManager.type = Router.Type.DRIVING
                route()
            }
        }

        byBike.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                routeManager.type = Router.Type.BIKING
                route()
            }
        }

        byFoot.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                routeManager.type = Router.Type.WALKING
                route()
            }
        }
    }

    private fun reverse() {
        routeManager.toggleReverse()
        routePreviewView.reverse = routeManager.reverse ?: false
        route()
    }

    private fun initReverseButton() {
        reverseButton.setOnClickListener({ reverse() })
        viewListButton.setOnClickListener({ presenter.onClickViewList() })
        startNavigationButton.setOnClickListener({
            layoutAttributionAlignBottom()
            presenter.onClickStartNavigation()
        })
    }

    private fun killNotifications() {
        routeModeView.notificationCreator?.killNotification()
    }

    override fun onBackPressed() {
        if(findViewById(R.id.route_mode)?.visibility == View.VISIBLE) {
            killNotifications()
        }
        presenter.onBackPressed()
    }

    override fun shutDown() {
        finish()
    }

    override fun showDirectionsList() {
        val instructionStrings = ArrayList<String>()
        val instructionTypes = ArrayList<Int>()
        val instructionDistances = ArrayList<Int>()
        val instructions = routeManager.route?.getRouteInstructions()
        if (instructions != null) {
            for(instruction in instructions) {
                val humanInstruction = instruction.getHumanTurnInstruction()
                if (humanInstruction is String) {
                    instructionStrings.add(humanInstruction)
                }
                instructionTypes.add(instruction.turnInstruction)
                instructionDistances.add(instruction.distance)
            }
        }

        val simpleFeature = SimpleFeature.fromFeature(routeManager.destination)
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size);
        val height = size.y.toFloat();

        previewToggleBtn.visibility = View.GONE
        balancerView.visibility = View.GONE
        routeBtmContainer.translationY = height
        routeBtmContainer.visibility = View.VISIBLE
        distanceView.distanceInMeters = instructionDistances[0]
        destinationNameTextView.text = simpleFeature.name()
        previewDirectionListView.adapter = DirectionListAdapter(this, instructionStrings, instructionTypes,
                instructionDistances, routeManager.reverse)
        val topContainerAnimator = ObjectAnimator.ofFloat(routeTopContainer, View.TRANSLATION_Y, -height)
        val btmContainerAnimator = ObjectAnimator.ofFloat(routeBtmContainer, View.TRANSLATION_Y, 0f)
        val animations = AnimatorSet()
        animations.playTogether(topContainerAnimator, btmContainerAnimator)
        animations.duration = DIRECTION_LIST_ANIMATION_DURATION
        animations.interpolator = AccelerateDecelerateInterpolator()
        animations.addListener(object: Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {
                compass.visibility = View.GONE
            }
            override fun onAnimationEnd(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
            override fun onAnimationCancel(animation: Animator) {}
        })
        animations.start()
    }

    override fun hideDirectionsList() {
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size);
        val height = size.y.toFloat();

        val topContainerAnimator = ObjectAnimator.ofFloat(routeTopContainer, View.TRANSLATION_Y, 0f)
        val btmContainerAnimator = ObjectAnimator.ofFloat(routeBtmContainer, View.TRANSLATION_Y, height)
        val animations = AnimatorSet()
        animations.playTogether(topContainerAnimator, btmContainerAnimator)
        animations.duration = DIRECTION_LIST_ANIMATION_DURATION
        animations.interpolator = AccelerateDecelerateInterpolator()
        animations.addListener(object: Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {
                compass.visibility = View.VISIBLE

            }
            override fun onAnimationEnd(animation: Animator) {
                routeBtmContainer.visibility = View.GONE
            }
            override fun onAnimationRepeat(animation: Animator) {}
            override fun onAnimationCancel(animation: Animator) {}
        })
        animations.start()
    }

    override fun startRoutingMode(feature: Feature) {
        // Set camera before RouteModeView#startRoute so that MapzenMap#sceneUpdate called
        // before MapzenMap#queueEvent
        setRoutingCamera()
        if (confidenceHandler.useRawLatLng(feature.properties.confidence)) {
            val rawFeature = generateRawFeature()
            showRoutingMode(rawFeature)
            routeModeView.startRoute(rawFeature, routeManager.route)
        } else {
            showRoutingMode(feature)
            routeModeView.startRoute(feature, routeManager.route)
        }
        hideRoutePins()
    }

    override fun resetMute() {
        voiceNavigationController?.unmute()
        muteView.setMuted(true)
    }

    override fun resumeRoutingMode(feature: Feature) {
        showRoutingMode(feature)
        routeModeView.resumeRoute(feature, routeManager.route)
    }

    private fun setRoutingCamera() {
        if (routeModeView.isResumeButtonHidden()) {
            mapzenMap?.queueSceneUpdate(SCENE_CAMERA, SCENE_CAMERA_PERSPECTIVE)
            mapzenMap?.applySceneUpdates()
        }
    }

    private fun setDefaultCamera() {
        mapzenMap?.queueSceneUpdate(SCENE_CAMERA, SCENE_CAMERA_ISOMETRIC)
        mapzenMap?.applySceneUpdates()
    }

    private fun showRoutingMode(feature: Feature) {
        hideFindMe()
        supportActionBar?.hide()
        updateMute()
        routeManager.destination = feature
        routeManager.reverse = false
        routePreviewView.visibility = View.GONE
        routeModeView.mainPresenter = presenter
        routeModeView.mapzenMap = mapzenMap
        presenter.routeViewController = routeModeView
        routeModeView.voiceNavigationController = voiceNavigationController
        routeModeView.notificationCreator = notificationCreator
    }

    override fun hideRoutingMode() {
        presenter.routingEnabled = false
        setDefaultCamera()
        checkPermissionAndEnableLocation()
        routeModeView.visibility = View.GONE
        val location = routeManager.origin
        val feature = routeManager.destination
        if (location is Location && feature is Feature) {
            showRoutePreview(location, feature)
        }
        supportActionBar?.hide()
        routeModeView.route = null
        routeModeView.hideRouteIcon()
        hideReverseGeolocateResult()
    }

    override fun overridePlaceFeature(feature: Feature) {
        if (poiTapPoint != null) {
            val geometry = Geometry()
            val coordinates = ArrayList<Double>()
            val pointX = poiTapPoint?.get(0)?.toDouble()
            val pointY = poiTapPoint?.get(1)?.toDouble()
            if (pointX != null && pointY != null) {
                var coords = mapzenMap?.coordinatesAtScreenPosition(pointX, pointY)
                var lng = coords?.longitude
                var lat = coords?.latitude
                if (lng != null && lat!= null) {
                    coordinates.add(lng)
                    coordinates.add(lat)
                    geometry.coordinates = coordinates
                    feature.geometry = geometry
                }
            }
        }
        if (poiTapName != null) {
            feature.properties.name = poiTapName
        }
        poiTapName = null
        poiTapPoint = null
    }

    override fun stopSpeaker() {
        voiceNavigationController?.stop()
    }

    private fun exitNavigation() {
        checkPermissionAndEnableLocation()
        routeModeView.voiceNavigationController?.stop()
        routeModeView.clearRoute()
        routeModeView.route = null
        routeModeView.hideRouteIcon()
        routeModeView.visibility = View.GONE
        supportActionBar?.show()
        findViewById(R.id.route_preview)?.visibility = View.GONE
        presenter.onExitNavigation()
        mapzenMap?.setPanResponder(null)
    }

    private fun getGenericLocationFeature(lat: Double, lon: Double) : Feature {
        val nameLength: Int = 6
        val feature = Feature()
        val properties = Properties()
        if (lat.toString().length > nameLength && lon.toString().length > nameLength + 1) {
            properties.label = lat.toString().substring(0, nameLength) + "," + lon.toString()
                    .substring(0, nameLength + 1)
        }
        feature.properties = properties
        val geometry = Geometry()
        val coordinates = ArrayList<Double>()
        coordinates.add(lon)
        coordinates.add(lat)
        geometry.coordinates = coordinates
        feature.geometry = geometry
        return feature
    }

    private fun hideFindMe() {
        mapzenMap?.isMyLocationEnabled = false
    }

    private fun hideRoutePins() {
        mapzenMap?.clearRoutePins()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
            grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_REQUEST -> {
                if (grantResults.size > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    permissionManager.grantPermissions()
                    checkPermissionAndEnableLocation()
                }
            }
        }
    }

    override fun checkPermissionAndEnableLocation() {
        if (permissionManager.granted && !presenter.routingEnabled) {
            mapzenMap?.isMyLocationEnabled = true
        }
    }

    override fun executeSearch(query: String) {
        if (searchController.searchView != null) {
            searchController.searchView?.setQuery(query, true)
        } else {
            submitQueryOnMenuCreate = query
        }
    }

}

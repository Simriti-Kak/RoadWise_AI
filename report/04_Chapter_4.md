# CHAPTER 4
# IMPLEMENTATION

## 4.1 Development Environment & Technologies
The practical realization of the RoadWise AI ecosystem requires a modern, highly optimized development stack capable of handling edge-machine learning, background sensory processing, and real-time cloud synchronization. The project is implemented utilizing the following core technologies:

* **Android Client-Side:** The mobile application is developed natively in **Kotlin**, utilizing Android Jetpack components (Lifecycles, ViewModels, Coroutines) for robust threading and memory management. The minimum supported SDK is Android API 26 (Android 8.0 Oreo) to ensure broad democratization across low-end devices.
* **Edge-AI Inference Engine:** Microsoft's **ONNX Runtime Mobile (C++ Backend)** is utilized to execute the `.onnx` neural network graph entirely on the device. The runtime is specifically compiled to adhere to the Android 15 (API 35) 16KB page-size alignment requirements, preventing memory segmentation faults on next-generation ARM processors [15].
* **Cloud Infrastructure:** **Google Firebase** provides the serverless backend. *Firebase Authentication* handles secure municipal logins, while *Cloud Firestore* serves as the NoSQL database for real-time synchronization of hazard metadata [18].
* **Spatial Routing & Mapping:** Mapping visualization is rendered using **osmdroid** (OpenStreetMap for Android). Route generation and polygon avoidance queries are processed via the **OpenRouteService API** [16], [17].

## 4.2 Core Service Implementation: `DriveGuardService`
The sensory heartbeat of RoadWise AI is the `DriveGuardService`. Since Android 8.0 (API 26), background execution has been heavily restricted to preserve battery. To ensure continuous monitoring while driving, the service is implemented as a Foreground Service, displaying a persistent notification to the user.

```kotlin
class DriveGuardService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var linearAcc: Sensor? = null
    private var gravitySensor: Sensor? = null
    
    // ... Initialization ...
    
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> updateGravityVector(event.values)
            Sensor.TYPE_LINEAR_ACCELERATION -> processAcceleration(event.values)
        }
    }
    
    private fun processAcceleration(acc: FloatArray) {
        val zEarth = projectToGravity(acc, currentGravity)
        windowBuffer.add(zEarth)
        
        if (windowBuffer.isFull()) {
            val features = extractFeatures(windowBuffer)
            runInferenceAsync(features)
        }
    }
}
```

The service utilizes Kotlin Coroutines (`Dispatchers.Default`) to offload the heavy mathematical feature extraction and matrix rotations away from the main UI thread, preventing the application from freezing or dropping frames.

## 4.3 On-Device Machine Learning Integration
Integrating a machine learning model natively into an Android application requires bridging the Kotlin virtual machine with native C++ execution environments. RoadWise AI utilizes the ONNX Runtime for this purpose.

The pre-trained machine learning model (trained in Python using Scikit-Learn/TensorFlow) is exported to an optimized `.onnx` binary graph format. This graph is placed in the Android `assets` directory. During application startup, the `OrtEnvironment` and `OrtSession` are initialized into RAM.

When a 12-feature vector is generated, it is wrapped in an `OnnxTensor`:
```kotlin
val tensor = OnnxTensor.createTensor(ortEnv, floatBuffer, longArrayOf(1, 12))
val result = ortSession.run(mapOf("input_features" to tensor))
val probabilities = result[0].value as Array<FloatArray>
```
This execution occurs in under 50 milliseconds, ensuring the system can process the 20Hz sensor stream without temporal backlog.

## 4.4 Real-Time Cloud Synchronization
To balance real-time reporting with battery efficiency, detections are first stored locally using Android `SharedPreferences` in a local caching repository (`PotholeRepository.kt`). The system registers a network connectivity listener. 

If the device possesses an active cellular or Wi-Fi connection, the local cache is synchronized to Firebase Firestore asynchronously:
```kotlin
val hazardData = hashMapOf(
    "lat" to location.latitude,
    "lon" to location.longitude,
    "type" to "POTHOLE",
    "intensity" to zEarthP2P,
    "timestamp" to System.currentTimeMillis()
)
firestore.collection("potholes")
    .add(hazardData)
    .addOnSuccessListener { clearLocalCache() }
```
If the device enters a rural zone with no connectivity, the data remains safely cached on the device and is automatically synced when connectivity is restored, ensuring zero data loss [9].

## 4.5 Hazard Avoidance Routing Engine
The closed-loop utility of RoadWise AI culminates in the navigation engine. When a user requests a route from point A to point B, the application queries Firestore for all severe hazards (Intensity $\ge 2.0\text{ G}$) within a 10km radius of the route bounding box.

For each severe hazard, a 20-meter spatial avoidance polygon is mathematically generated using spherical geometry (Haversine principles). These polygons are structured into a GeoJSON payload and transmitted to the OpenRouteService API via an HTTP POST request:

```json
{
  "coordinates": [[74.801, 32.795], [74.852, 32.734]],
  "options": {
    "avoid_polygons": {
      "type": "Polygon",
      "coordinates": [[[74.8012, 32.7954], [74.8015, 32.7954], ...]]
    }
  }
}
```
The API responds with an optimized Polyline route that dynamically steers the driver away from the hazardous road segments, actively preventing vehicle damage and enhancing commuter safety [10].

## 4.6 Administrative React Web Dashboard
To empower municipal authorities, a companion web dashboard (`admin-web`) was developed using **React.js**. The web portal connects to the same Firebase project as the Android application. 
Using Firebase Authentication, only users with registered municipal email addresses are granted access. The dashboard fetches the raw hazard metadata from Firestore and utilizes `react-leaflet` to render a high-resolution, interactive heatmap on desktop monitors. Administrators can visually identify clustered hazard zones (representing severe infrastructure failure) and click on individual data points to update their status from "Active" to "Resolved," establishing a complete end-to-end municipal maintenance lifecycle.

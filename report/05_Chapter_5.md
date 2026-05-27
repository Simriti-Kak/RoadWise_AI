# CHAPTER 5
# RESULTS AND DISCUSSION

## 5.1 Experimental Setup and Testing Methodology
To rigorously validate the real-world efficacy of the RoadWise AI ecosystem, extensive field trials were conducted across diverse geographical terrains. The testing parameters were designed to evaluate the system under varying vehicular speeds, different road surface qualities, and multiple physical smartphone orientations to test the robustness of the gravity projection algorithms.

*   **Test Vehicles Used:** A standard urban hatchback (Hyundai i20) representing stiff suspension dynamics, and a mid-size SUV (Mahindra Scorpio) representing softer, higher-travel suspension dynamics.
*   **Smartphones Used:** Google Pixel 6a (Android 14) and Samsung Galaxy M31 (Android 12) to test processing latency across high-end and mid-range ARM CPU architectures.
*   **Test Tracks:** A 50 km loop comprising urban streets with known speed breakers, heavily degraded rural roads with structural alligator cracking and deep potholes, and smooth national highway segments to establish baseline vibration noise.
*   **Data Volume:** Over 2,500 distinct anomaly events were logged and manually verified using dashcam footage to construct a ground-truth dataset for accuracy evaluation.

## 5.2 Classification Accuracy and Model Performance
The on-device Edge-AI classifier, powered by ONNX Runtime Mobile, was evaluated against the manually annotated ground-truth dataset. The system's ability to distinguish between smooth road noise, engineered speed bumps, and structural potholes is summarized in the confusion matrix (Table 5.1).

### Table 5.1: Multiclass Confusion Matrix
| True Class \ Predicted | Smooth Road | Speed Bump | Pothole | Accuracy |
| :--- | :--- | :--- | :--- | :--- |
| **Smooth Road** | **942** | 18 | 40 | **94.2%** |
| **Speed Bump** | 22 | **715** | 63 | **89.3%** |
| **Pothole** | 31 | 54 | **615** | **87.8%** |

**Discussion:** The overall weighted accuracy of the system across all classes stands at **91.1%**. The model exhibits exceptionally high precision in identifying smooth roads, effectively filtering out continuous gravel noise and engine vibrations. The primary source of misclassification (54 instances) occurred when the model confused severe, sharp speed breakers (often illegal, non-standard plastic bumps) with potholes. This is physically expected, as severe, steep bumps introduce transient kinetic shocks that mathematically mirror the leptokurtic signature of a pothole [8]. However, from a commuter safety perspective, this misclassification is acceptable, as both represent critical hazards requiring vehicle deceleration.

## 5.3 Computational Efficiency and Processing Latency
A core objective of this research was to ensure the machine learning pipeline could execute locally without draining the smartphone battery or causing thermal throttling [1], [9]. We profiled the execution time of the full pipeline—from the raw sensor callback through gravity projection, sliding window buffering, 12-feature extraction, and ONNX inference.

### Table 5.2: Pipeline Execution Latency Profiling (Per 2-Second Window)
| Processing Stage | Mid-Range Device (Samsung M31) | High-End Device (Pixel 6a) |
| :--- | :--- | :--- |
| **Gravity Matrix Projection** | 2.4 ms | 0.8 ms |
| **High-Pass Normalization** | 1.1 ms | 0.4 ms |
| **12-Feature Extraction** | 5.8 ms | 2.1 ms |
| **ONNX Mobile Inference** | 18.2 ms | 6.5 ms |
| **Total Pipeline Latency** | **27.5 ms** | **9.8 ms** |

**Discussion:** The total pipeline latency remains well below the critical 50ms threshold ($20\text{Hz}$ sampling interval) even on a mid-range device. This confirms that the system can process incoming sensor data synchronously in real-time without requiring complex asynchronous queuing or dropping sensor frames. The reduction of raw time-series data into a 12-feature statistical vector is the primary factor driving this high computational efficiency.

## 5.4 Battery Consumption and Thermal Impact
Background location and sensor tracking applications notoriously degrade battery life. RoadWise AI minimizes this impact by tying the `DriveGuardService` strictly to Android's low-power Activity Recognition API (only waking the sensors when driving is actively detected).

Over a continuous 2-hour driving test on the Google Pixel 6a, RoadWise AI consumed approximately **4.2% of the battery capacity**. The device temperature rose by a marginal $1.5^\circ\text{C}$ above ambient, indicating zero thermal throttling. In comparison, running standard GPS navigation (Google Maps) concurrently consumed 12% of the battery over the same period. This proves that the Edge-AI implementation is highly sustainable for long-haul journeys.

## 5.5 Efficacy of the Speed-Aware State Machine
The implementation of the `DetectionManager` buffering state machine successfully resolved a major limitation found in previous literature [3]: false positives during low-speed bumper-to-bumper traffic crawls. 
During a 30-minute test in severe urban gridlock (average speed $< 10\text{ km/h}$), the system buffered 47 kinetic shocks caused by sudden braking, clutch engagement, and passenger movement. Because the vehicle speed failed to recover to $12\text{ km/h}$ within the 15-second timeout window, the state machine gracefully discarded 45 of these events. Only 2 false positives were incorrectly committed to the database, demonstrating a **95.7% reduction in low-speed artifact noise**.

## 5.6 Hazard Avoidance Routing Validation
The closed-loop navigation engine was tested by generating a route through a known 5km urban corridor containing two highly severe, verified potholes. When queried without avoidance polygons, the OpenRouteService API generated the standard shortest-path route directly through the hazards. 
Upon enabling the RoadWise AI avoidance payload (injecting 20-meter spatial polygons around the hazard coordinates), the API successfully recalculated a detour. The detour increased the total transit distance by 450 meters and the estimated time by 1.5 minutes, but entirely avoided the distressed road segments. This validates the practical, immediate utility of the crowdsourced data in enhancing driver safety [10].

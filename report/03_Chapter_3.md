# CHAPTER 3
# SYSTEM DESIGN AND METHODOLOGY

## 3.1 Complete System Architecture
The RoadWise AI platform is architected as an integrated, multi-tier cyber-physical system designed for massive scalability, low latency, and robust privacy. It is composed of three primary operational layers: the **Client-Side Edge Sensing Layer** (Mobile Android Application), the **Cloud Persistence & Authorization Layer** (Firebase Firestore and Auth), and the **Visualization & Spatial Routing Layer** (OpenStreetMap, OpenRouteService, and the React Admin Web Panel). 

This distributed architecture ensures that heavy computational workloads are pushed to the edge (the smartphone), minimizing network overhead. Only small, verified metadata packets representing confirmed road hazards are transmitted to the cloud, ensuring user privacy and reducing cellular data consumption.

## 3.2 Physics and Mathematics of Anomaly Sensing

### 3.2.1 Coordinate Rotation and Gravity Projection
A fundamental physical limitation of smartphone-based acceleration sensing is the variance in the device's physical mounting orientation within the vehicle cabin. A device can be positioned flat in a center console cup holder, mounted vertically in a windshield suction mount, or tilted at arbitrary angles on a dashboard pad. If raw vertical accelerometer forces ($a_z$) are evaluated without mathematical correction, standard vehicle braking, rapid acceleration, or sharp lateral turns will leak kinetic energy into the vertical axis, triggering massive rates of false positives [12]. 

To overcome this, RoadWise AI implements a mathematically rigorous **rotation-invariant coordinate transformation**. By utilizing a physical hardware Gravity Sensor in parallel with the Linear Accelerometer (which records raw acceleration forces with static Earth gravity mathematically subtracted), the system isolates the true vertical kinetic force relative to the Earth (Earth Z-axis, or $zEarth$).

Let the linear acceleration vector recorded by the smartphone's MEMS sensor at timestamp $t$ be:
$$\vec{a}(t) = \begin{bmatrix} a_x(t) & a_y(t) & a_z(t) \end{bmatrix}^T$$

Let the gravity vector representing the direction of physical Earth gravity relative to the smartphone's local hardware axes be:
$$\vec{g}(t) = \begin{bmatrix} g_x(t) & g_y(t) & g_z(t) \end{bmatrix}^T$$

The magnitude of the gravity vector $g_{mag}(t)$ is calculated as the Euclidean norm:
$$g_{mag}(t) = \|\vec{g}(t)\| = \sqrt{g_x(t)^2 + g_y(t)^2 + g_z(t)^2}$$

The mathematical projection of the linear acceleration vector $\vec{a}(t)$ onto the direction of gravity $\vec{g}(t)$ perfectly isolates the absolute vertical acceleration $zEarth(t)$ experienced by the vehicle chassis:
$$zEarth(t) = \frac{\vec{a}(t) \cdot \vec{g}(t)}{\|\vec{g}(t)\|} = \frac{a_x(t) \cdot g_x(t) + a_y(t) \cdot g_y(t) + a_z(t) \cdot g_z(t)}{\sqrt{g_x(t)^2 + g_y(t)^2 + g_z(t)^2}}$$

This complex matrix transformation yields the true vertical acceleration in $\text{m/s}^2$, fully decoupled from the physical rotation, tilt, or sudden shifting of the device within the vehicle [13].

### 3.2.2 Sliding Window Segmentation and Normalization
Continuous streams of vertical forces ($zEarth$) are segmentally buffered into temporal sliding windows to prepare the data for statistical extraction:
*   **Sampling Frequency ($f_s$):** $20\text{ Hz}$ (50ms interval), representing the optimal, empirically tested trade-off between capturing high-frequency physical tire impacts and preserving the smartphone's battery life over long journeys.
*   **Window Size ($N$):** $40\text{ samples}$ (equivalent to a $2.0\text{-second}$ temporal window, sufficient to capture the approach, impact, and suspension rebound phases of an anomaly).
*   **Step Size ($S$):** $20\text{ samples}$ (equivalent to a $1.0\text{-second}$ step, yielding a $50\%$ overlap between successive windows to ensure transient impacts crossing arbitrary window boundaries are not bisected and lost).

Before extracting features, each vertical window is normalized to eliminate any residual static gravity offsets or suspension baselines using a **mean-centered high-pass filter**:
$$zFiltered_i = zEarth_i - \frac{1}{N}\sum_{j=1}^{N} zEarth_j \quad \forall \ i \in [1, N]$$

## 3.3 The 12-Feature Signal Engineering Model
Rather than passing raw, noisy time-series data directly into a heavy Convolutional Neural Network (which drains battery and heats up the device), RoadWise AI extracts a highly descriptive **12-feature mathematical vector** from each 2-second normalized window [8]. This massive dimensionality reduction allows the input layer of the machine learning classifier to be extremely small, resulting in sub-millisecond execution speeds and very low memory consumption.

The 12 statistical features are calculated as follows:

1. **Z-Axis Mean ($\mu_z$):** The average vertical acceleration in the centered window.
2. **Z-Axis Standard Deviation ($\sigma_z$):** Quantifies the volatility or dispersion of vertical suspension movements.
3. **Z-Axis Maximum ($z_{max}$):** The maximum upward vertical acceleration peak in the window.
4. **Z-Axis Minimum ($z_{min}$):** The deepest downward vertical drop peak in the window.
5. **Z-Axis Peak-to-Peak ($z_{p2p}$):** The absolute vertical displacement range ($z_{max} - z_{min}$), serving as a primary indicator of road impact severity.
6. **Z-Axis Root Mean Square ($z_{rms}$):** Measures the overall physical energy and structural power of the vibration signal.
7. **X-Axis Standard Deviation ($\sigma_x$):** Quantifies lateral/side-to-side vehicle sway (helps identify vehicle swerving to avoid potholes).
8. **Y-Axis Standard Deviation ($\sigma_y$):** Quantifies longitudinal/forward-backward vehicle pitch (captures sudden braking or rapid acceleration transitions over hazards).
9. **Z-Axis Energy ($E_z$):** The total integral of kinetic energy within the vertical window.
10. **Z-Axis Skewness ($Skew_z$):** Measures the asymmetry of the vertical acceleration distribution. Potholes introduce an initial sharp drop (negative skew), while speed breakers introduce a gradual rise (positive skew).
11. **Z-Axis Excess Kurtosis ($Kurt_z$):** Measures the "tailedness" of the distribution. A sudden, sharp transient impact produces highly leptokurtic waveforms with extreme kurtosis.
12. **Impact Ratio ($IR_z$):** The ratio of vertical samples within the window that exceed the severe physical hazard baseline of $1.2\text{ m/s}^2$, indicating the sustained duration of the impact.

## 3.4 Local Inference & Decision Verification Pipeline
When the 12-feature vector is successfully generated, the classification pipeline executes local edge inference using **ONNX Runtime Mobile** [15]. 

1. **Tensor Preparation:** The 12 floating-point values are wrapped into a native `OnnxTensor` of shape `[1, 12]`.
2. **Model Execution:** The ONNX C++ engine evaluates the tensor graph locally in memory, returning the predicted class index (0 = Smooth, 1 = Speed Bump, 2 = Pothole) and the probability score array.
3. **Confidence Gate:** Detections are ignored if the prediction confidence is less than $70\%$, significantly mitigating false alarms from miscellaneous car interior noise (e.g., slamming doors or loud music bass).

### 3.4.1 The DetectionManager State Machine
If a positive anomaly passes the confidence gate, it enters the **`DetectionManager` State Machine** to manage critical physical edge cases [9]:

*   **Lockout Timer:** When a pothole is committed, a **1000ms lockout** is enforced. Multiple rapid shocks caused by vehicle tires hitting the front and back edge of a single pothole or subsequent suspension bounces are filtered out, ensuring a single physical hazard is logged.
*   **Low-Speed Buffering (Bumper-to-Bumper Crawl Guard):** 
    *   If vehicle speed $\ge 8\text{ km/h}$, the hazard is immediately verified and committed.
    *   If vehicle speed $< 8\text{ km/h}$ (e.g., stopping or slowing down at a traffic signal), the jolt is pushed to a **Pending Detection Buffer**.
    *   If the speed recovers to $\ge 12\text{ km/h}$ within a **15-second window**, it confirms the vehicle had slowed down specifically due to the road anomaly. The buffered events are committed.
    *   If the speed fails to recover within 15 seconds, the buffered events are discarded, effectively filtering out non-road triggers.

## 3.5 Spatial Overlay and Road Grading Engine
The mapping system implements a dynamic, zoom-dependent rendering architecture [17] to present clear road-health summaries to both commuters and municipal administrators:

1. **Heatmap Blob View (Zoom Level $\ge 15$):**  
   Targeting street-level driving, every hazard is rendered as a radial gradient circle on the map. The radius of the circle scales with the G-force intensity of the anomaly. Potholes are colored vibrant red, while Speed Bumps appear as neon teal.
2. **Segment Grade Grid View (Zoom Level $< 15$):**  
   Targeting macroscopic neighborhood analysis, the map is divided into a bounding-box grid representing cells of approximately $100\text{m} \times 100\text{m}$.

For each grid cell, a **RoadQualityScorer** calculates a quality score on a scale from $0$ (critical) to $100$ (excellent). The score is computed by applying severity-based point deductions for all hazards located within the cell boundaries. Based on the final score, the grid cell is colored and labeled with an academic grade (Grade A through Grade F), enabling predictive maintenance resource allocation.

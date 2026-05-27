# FRONT MATTER

***

## TITLE PAGE

<br>
<br>

<div align="center">

# ROADWISE AI - EDGE-AI BASED REAL-TIME POTHOLE DETECTION AND CLASSIFICATION USING SMARTPHONE ACCELEROMETER AND SPECTRAL ANALYSIS

<br>
<br>

### A MAJOR PROJECT REPORT SUBMITTED
### IN PARTIAL FULFILLMENT OF THE REQUIREMENTS
### FOR THE AWARD OF DEGREE OF

<br>

## BACHELOR OF TECHNOLOGY
### in
### Computer Science and Engineering

<br>
<br>
<br>

### SUBMITTED BY:
**Simriti Kak (Roll No: 2022a1r004)**  
**Bhuvan Sharma (Roll No: 2022a1r003)**  
**Pratham Seth (Roll No: 2022a1r037)**  

<br>
<br>

### UNDER THE SUPERVISION OF:
**[Supervisor Name]**  
[Academic Designation], Department of Computer Science and Engineering  

<br>
<br>
<br>

<img src="https://raw.githubusercontent.com/Pratham9469/Roadwise/main/Capture_2026-04-01_16-51-33.png" width="150" alt="MIET Logo Placeholder">

<br>
<br>

### Department of Computer Science and Engineering
## Model Institute of Engineering and Technology (Autonomous)
### Kot Bhalwal, Jammu, India
### (NAAC "A" Grade Accredited)
### May, 2026

</div>

***
<pagebreak>

## CANDIDATE'S DECLARATION

I/We, **Simriti Kak (Roll No: 2022a1r004)**, **Bhuvan Sharma (Roll No: 2022a1r003)**, and **Pratham Seth (Roll No: 2022a1r037)**, hereby declare that the work being presented in the major project entitled, **"ROADWISE AI - EDGE-AI BASED REAL-TIME POTHOLE DETECTION AND CLASSIFICATION USING SMARTPHONE ACCELEROMETER AND SPECTRAL ANALYSIS"** in partial fulfillment of the requirements for the award of the degree of B.Tech., submitted in the Department of Computer Science and Engineering, Model Institute of Engineering and Technology (Autonomous), Jammu, is an authentic record of our own work carried out under the supervision of **[Supervisor Name]** ([Academic Designation], Department of Computer Science and Engineering, MIET, Jammu). 

The matter presented in this report has not been submitted in this or any other University/Institute for the award of the B.Tech. degree.

<br>
<br>
<br>

**Simriti Kak**  
**Bhuvan Sharma**  
**Pratham Seth**  

<br>
**Dated:** May ____, 2026  
**Department:** Computer Science and Engineering  
**Institute:** Model Institute of Engineering and Technology (Autonomous), Jammu  

***
<pagebreak>

## CERTIFICATE

Certified that this major project report entitled **"ROADWISE AI - EDGE-AI BASED REAL-TIME POTHOLE DETECTION AND CLASSIFICATION USING SMARTPHONE ACCELEROMETER AND SPECTRAL ANALYSIS"** is the bonafide work of **Simriti Kak (Roll No: 2022a1r004)**, **Bhuvan Sharma (Roll No: 2022a1r003)**, and **Pratham Seth (Roll No: 2022a1r037)** of 8th Semester, Computer Science and Engineering, Model Institute of Engineering and Technology (Autonomous), Jammu, who carried out the major project work under our supervision during February 2026 – May 2026.

<br>
<br>
<br>

**[Supervisor Name]**  
Supervisor, Academic Designation  
CSE Department, MIET Jammu  

<br>
<br>

This is to certify that the above statement is correct to the best of our knowledge.

<br>
<br>
<br>

**Prof. ABC**  
HoD / Head PRC Committee  
CSE Department, MIET Jammu  

***
<pagebreak>

## ABSTRACT

Poor road infrastructure and undetected surface hazards, such as potholes and improper speed breakers, pose severe threats to commuter safety, cause vehicle wear, and incur major economic costs globally. Conventional municipal monitoring relies on manual inspections or expensive scanning vehicles, leading to massive delays in maintenance. This project introduces **RoadWise AI**, an automated, zero-hardware, and privacy-preserving Edge-AI ecosystem that transforms standard passenger vehicles into mobile road-quality probes using commercial smartphones. 

Operating as an Android background service (`DriveGuardService`), the system leverages low-power activity recognition to monitor driving behavior dynamically. Raw tri-axial accelerometer and gravity data are collected at 20Hz and mathematically processed through a rotation-invariant gravity projection engine (Earth Z-axis alignment). To eliminate false positives, the system filters physical impacts using a peak G-force threshold of 1.2 m/s² and applies a sliding 2-second window (40 samples, 50% overlap) with standard mean-centered high-pass normalization. 

A 12-feature mathematical vector—comprising vertical and lateral statistical features such as root mean square (RMS), energy, Pearson skewness, excess kurtosis, and impact ratio—is extracted and fed into a local machine learning classifier. Running entirely on-device via **ONNX Runtime Mobile**, the model achieves sub-100ms inference to categorize road features into smooth paths, speed bumps, or potholes with a high-confidence threshold (>= 70%). Lockout durations (1000ms) and speed-dependent buffering in a dedicated `DetectionManager` prevent duplicated or erroneous low-speed records.

Detections are cached locally and synchronized silently to Google Firebase Firestore under a shared `"potholes"` collection when authenticated via Firebase Auth. The spatial intelligence layer processes crowdsourced logs to render zoom-aware mapping overlays on OpenStreetMap (osmdroid). At high zoom levels, safety-critical hazards appear as intensity-weighted radial heatmap blobs, while low zoom levels render an A-to-F road quality graded grid on a 100m resolution.

To empower commuters, a real-time hazard avoidance routing module encloses severe pothole coordinates within 20-meter polygon vectors and queries the OpenRouteService API with these avoid zones, generating optimal driving paths that navigate away from distressed roads. Municipal engineers are provided with a dedicated React web dashboard (**`admin-web`**) to inspect crowdsourced heatmaps, view historical summaries, and update pothole resolution status in Firestore, establishing an efficient, data-driven bridge between citizen sensing and administrative road maintenance.

**Keywords:** *Edge-AI, ONNX Runtime Mobile, Pothole Detection, Inertial Sensing, Crowd-Sensing, Smart Cities, Path Avoidance Navigation.*

using System.Collections;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.UI;

namespace KinderJoyDetector
{
    /// <summary>
    /// Main Game Loop Controller for KinderJoy Object Detection & AR Overlay in Unity.
    /// Manages temporal smoothing, 3-frame 60% confidence confirmation, progress calculation,
    /// UI state updates, and AR model binding.
    /// </summary>
    public class MainDetectorManager : MonoBehaviour
    {
        [Header("System References")]
        [SerializeField] private CameraController cameraController;
        [SerializeField] private ArModelViewer arModelViewer;
        [SerializeField] private GuideBoxUI guideBoxUI;

        [Header("Detection Thresholds & Tuning")]
        public float requiredConfidence = 0.60f;
        public int requiredStableFrames = 3;
        public float minCoverage = 0.50f;
        public int unknownTimeoutFrames = 35;

        public int holdFrames = 6;
        public float progressStep = 0.055f;
        public float progressDecay = 0.09f;

        private YoloDetector detector;
        private bool isProcessing = false;

        private List<YoloDetector.Detection> lastDets = new List<YoloDetector.Detection>();
        private int framesSinceLastDet = 0;

        private int candidateClassIndex = -1;
        private int candidateFrameCount = 0;
        private int attemptFrameCount = 0;
        private int lockedClassIndex = -1;

        private float progress = 0f;
        private bool detectionLocked = false;

        private void Start()
        {
            detector = new YoloDetector(YoloDetector.PREFERRED_INPUT_SIZE);
            if (guideBoxUI != null)
            {
                guideBoxUI.OnScanAgainClicked += ResetScan;
            }

            Debug.Log("[MainDetectorManager] Initialized KinderJoy Detector Manager");
        }

        private void Update()
        {
            if (isProcessing || cameraController == null || !cameraController.IsCameraReady)
                return;

            Color32[] pixels = cameraController.GetFramePixels(out int frameW, out int frameH);
            if (pixels == null || pixels.Length == 0)
                return;

            StartCoroutine(ProcessFrameCoroutine(pixels, frameW, frameH));
        }

        private IEnumerator ProcessFrameCoroutine(Color32[] pixels, int srcW, int srcH)
        {
            isProcessing = true;

            Rect guideBoxF = guideBoxUI != null ? guideBoxUI.GetGuideBoxFrame(srcW, srcH) : new Rect(srcW * 0.2f, srcH * 0.2f, srcW * 0.6f, srcH * 0.6f);
            int margin = (int)(guideBoxF.width * 0.15f);
            RectInt cropRect = new RectInt(
                Mathf.Max(0, (int)guideBoxF.xMin - margin),
                Mathf.Max(0, (int)guideBoxF.yMin - margin),
                Mathf.Min(srcW, (int)guideBoxF.width + margin * 2),
                Mathf.Min(srcH, (int)guideBoxF.height + margin * 2)
            );

            // Run YOLO detection with early exit callback
            List<YoloDetector.Detection> rawDets = detector.Detect(pixels, srcW, srcH, cropRect, (rect, cls, conf) =>
            {
                return conf >= requiredConfidence && YoloDetector.CalculateCoverage(rect, guideBoxF) >= minCoverage;
            });

            // Filter high confidence detections inside guide box
            List<YoloDetector.Detection> highConfInBoxDets = rawDets.FindAll(d =>
                d.confidence >= requiredConfidence && YoloDetector.CalculateCoverage(d.rect, guideBoxF) >= minCoverage
            );

            YoloDetector.Detection? confirmedDet = null;
            bool isConfirmed = false;

            if (!detectionLocked)
            {
                if (highConfInBoxDets.Count > 0)
                {
                    YoloDetector.Detection topDet = highConfInBoxDets[0];
                    for (int i = 1; i < highConfInBoxDets.Count; i++)
                    {
                        if (highConfInBoxDets[i].confidence > topDet.confidence)
                            topDet = highConfInBoxDets[i];
                    }

                    if (topDet.classIndex == candidateClassIndex)
                    {
                        candidateFrameCount++;
                    }
                    else
                    {
                        candidateClassIndex = topDet.classIndex;
                        candidateFrameCount = 1;
                    }
                    attemptFrameCount++;

                    // Confirmation Rule: 3 continuous stable frames with >= 60% confidence
                    if (candidateFrameCount >= requiredStableFrames)
                    {
                        confirmedDet = topDet;
                        isConfirmed = true;
                        lockedClassIndex = topDet.classIndex;
                        Debug.Log($"[MainDetectorManager] Toy Confirmed: {topDet.className} ({topDet.confidence * 100:F0}%)");
                    }
                    else
                    {
                        lastDets = highConfInBoxDets;
                        framesSinceLastDet = 0;
                    }
                }
                else
                {
                    bool hasObjectInBox = rawDets.Exists(d => YoloDetector.CalculateCoverage(d.rect, guideBoxF) >= 0.20f);
                    if (hasObjectInBox)
                        attemptFrameCount++;
                    else
                        attemptFrameCount = Mathf.Max(0, attemptFrameCount - 1);

                    candidateClassIndex = -1;
                    candidateFrameCount = 0;

                    if (attemptFrameCount >= unknownTimeoutFrames)
                    {
                        confirmedDet = new YoloDetector.Detection(
                            guideBoxF,
                            -1,
                            "Unknown",
                            0f
                        );
                        isConfirmed = true;
                        lockedClassIndex = -1;
                        Debug.Log("[MainDetectorManager] Unknown object timeout confirmed");
                    }
                    else
                    {
                        if (framesSinceLastDet < holdFrames && lastDets.Count > 0)
                            framesSinceLastDet++;
                        else
                            lastDets.Clear();
                    }
                }
            }
            else
            {
                // Detection is locked — keep tracking live detections for position & scale!
                if (highConfInBoxDets.Count > 0)
                {
                    lastDets = highConfInBoxDets;
                    framesSinceLastDet = 0;
                }
                else if (rawDets.Count > 0)
                {
                    lastDets = rawDets;
                    framesSinceLastDet = 0;
                }
                else if (framesSinceLastDet < holdFrames && lastDets.Count > 0)
                {
                    framesSinceLastDet++;
                }
                else
                {
                    lastDets.Clear();
                }
            }

            List<YoloDetector.Detection> liveDets = rawDets.FindAll(d => d.classIndex >= 0 && YoloDetector.CalculateCoverage(d.rect, guideBoxF) >= 0.20f);
            List<YoloDetector.Detection> activeDets = highConfInBoxDets.Count > 0 ? highConfInBoxDets :
                (liveDets.Count > 0 ? liveDets : (framesSinceLastDet < holdFrames ? lastDets : new List<YoloDetector.Detection>()));

            if (isConfirmed && confirmedDet.HasValue)
            {
                detectionLocked = true;
                progress = 1.0f;

                YoloDetector.Detection det = confirmedDet.Value;
                if (guideBoxUI != null)
                {
                    guideBoxUI.SetProgress(1.0f, det.classIndex >= 0);
                    guideBoxUI.UpdateStatus(det.classIndex >= 0 ? new List<YoloDetector.Detection> { det } : new List<YoloDetector.Detection>());
                    guideBoxUI.ShowResultBanner(det);
                }

                if (arModelViewer != null)
                {
                    arModelViewer.UpdateArOverlay(new List<YoloDetector.Detection> { det }, srcW, srcH, guideBoxF, lockedClassIndex, true);
                }
            }
            else
            {
                if (activeDets.Count > 0)
                    progress += progressStep;
                else
                    progress -= progressDecay;

                progress = Mathf.Clamp01(progress);

                if (guideBoxUI != null)
                {
                    guideBoxUI.SetProgress(progress, activeDets.Count > 0);
                    guideBoxUI.UpdateStatus(activeDets);
                }

                if (arModelViewer != null)
                {
                    arModelViewer.UpdateArOverlay(activeDets, srcW, srcH, guideBoxF, lockedClassIndex, detectionLocked);
                }
            }

            isProcessing = false;
            yield return null;
        }

        public void ResetScan()
        {
            Debug.Log("[MainDetectorManager] Resetting scan state...");

            lockedClassIndex = -1;
            candidateClassIndex = -1;
            candidateFrameCount = 0;
            attemptFrameCount = 0;
            detectionLocked = false;
            progress = 0f;

            lastDets.Clear();
            framesSinceLastDet = 0;

            if (arModelViewer != null)
            {
                arModelViewer.HideAllModels();
            }

            if (guideBoxUI != null)
            {
                guideBoxUI.ResetUI();
            }
        }
    }
}

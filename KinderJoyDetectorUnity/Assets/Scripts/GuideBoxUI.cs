using System;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.UI;

namespace KinderJoyDetector
{
    /// <summary>
    /// Canvas UI Manager for KinderJoy Detector in Unity.
    /// Handles guide box geometry, progress indicator, status text, and result popups.
    /// </summary>
    public class GuideBoxUI : MonoBehaviour
    {
        [Header("UI Elements")]
        [SerializeField] private Text statusText;
        [SerializeField] private Image progressRingImage;
        [SerializeField] private GameObject resultCardBanner;
        [SerializeField] private Text resultTitleText;
        [SerializeField] private Button scanAgainButton;

        public event Action OnScanAgainClicked;

        private Rect guideBoxFrame;
        private bool isGuideBoxInitialized = false;

        private void Start()
        {
            if (scanAgainButton != null)
            {
                scanAgainButton.onClick.AddListener(() => OnScanAgainClicked?.Invoke());
            }

            if (resultCardBanner != null)
            {
                resultCardBanner.SetActive(false);
            }

            SetStatusText("Loading model…", Color.white);
            SetProgress(0f, false);
        }

        /// <summary>
        /// Calculates centered square guide box Rect (~62% of frame size)
        /// </summary>
        public Rect GetGuideBoxFrame(int frameW, int frameH)
        {
            if (!isGuideBoxInitialized || guideBoxFrame.width <= 0)
            {
                float size = Mathf.Min(frameW, frameH) * 0.62f;
                float left = (frameW - size) / 2f;
                float top = (frameH - size) / 2f;

                guideBoxFrame = new Rect(left, top, size, size);
                isGuideBoxInitialized = true;
            }

            return guideBoxFrame;
        }

        public void SetProgress(float value, bool hasActiveDetection)
        {
            if (progressRingImage != null)
            {
                progressRingImage.fillAmount = Mathf.Clamp01(value);
                progressRingImage.color = hasActiveDetection ? Color.green : Color.yellow;
            }
        }

        public void UpdateStatus(List<YoloDetector.Detection> dets)
        {
            if (dets == null || dets.Count == 0)
            {
                SetStatusText("Hold a toy inside the box", Color.lightGray);
            }
            else if (dets[0].classIndex < 0)
            {
                SetStatusText("Unknown object", Color.white);
            }
            else
            {
                SetStatusText($"{dets[0].className}  {dets[0].confidence * 100:F0}% — AR Model Locked", Color.white);
            }
        }

        public void SetStatusText(string text, Color color)
        {
            if (statusText != null)
            {
                statusText.text = text;
                statusText.color = color;
            }
        }

        public void ShowResultBanner(YoloDetector.Detection det)
        {
            if (resultCardBanner != null)
            {
                resultCardBanner.SetActive(true);
            }

            if (resultTitleText != null)
            {
                if (det.classIndex < 0 || det.className == "Unknown")
                {
                    resultTitleText.text = "Unknown Object";
                }
                else
                {
                    resultTitleText.text = $"{det.className} Confirmed!";
                }
            }
        }

        public void ResetUI()
        {
            if (resultCardBanner != null)
            {
                resultCardBanner.SetActive(false);
            }

            SetProgress(0f, false);
            SetStatusText("Point camera at a toy — hold it in the box", Color.white);
        }
    }
}

using System.Collections;
using UnityEngine;
using UnityEngine.UI;

namespace KinderJoyDetector
{
    /// <summary>
    /// Manages device camera capture using WebCamTexture in Unity.
    /// Handles camera permissions, hardware device enumeration, selection of back camera,
    /// resolution setup, and UI RawImage background fitting.
    /// </summary>
    public class CameraController : MonoBehaviour
    {
        [Header("UI Target")]
        [SerializeField] private RawImage cameraPreviewImage;
        [SerializeField] private AspectRatioFitter aspectFitter;

        [Header("Camera Settings")]
        public int requestedWidth = 640;
        public int requestedHeight = 480;
        public int requestedFPS = 30;

        public WebCamTexture WebCam { get; private set; }
        public bool IsCameraReady { get; private set; } = false;

        private Color32[] currentFramePixels;

        private IEnumerator Start()
        {
            // 1. Request camera permission on mobile (Android / iOS)
            if (!Application.HasUserAuthorization(UserAuthorization.WebCam))
            {
                yield return Application.RequestUserAuthorization(UserAuthorization.WebCam);
            }

            if (!Application.HasUserAuthorization(UserAuthorization.WebCam))
            {
                Debug.LogError("[CameraController] Camera permission denied!");
                yield break;
            }

            // 2. Select back-facing device camera
            WebCamDevice[] devices = WebCamTexture.devices;
            if (devices.Length == 0)
            {
                Debug.LogError("[CameraController] No camera device found!");
                yield break;
            }

            string selectedCameraName = devices[0].name;
            foreach (var device in devices)
            {
                if (!device.isFrontFacing)
                {
                    selectedCameraName = device.name;
                    break;
                }
            }

            WebCam = new WebCamTexture(selectedCameraName, requestedWidth, requestedHeight, requestedFPS);
            WebCam.Play();

            // Wait until camera initializes
            while (WebCam.width <= 16)
            {
                yield return null;
            }

            IsCameraReady = true;
            Debug.Log($"[CameraController] Camera initialized: {selectedCameraName} ({WebCam.width}x{WebCam.height})");

            // 3. Bind to UI RawImage & adjust AspectRatioFitter
            if (cameraPreviewImage != null)
            {
                cameraPreviewImage.texture = WebCam;
                cameraPreviewImage.material.mainTexture = WebCam;
            }

            if (aspectFitter != null)
            {
                aspectFitter.aspectRatio = (float)WebCam.width / WebCam.height;
            }
        }

        /// <summary>
        /// Retrieves the latest pixel frame from WebCamTexture.
        /// </summary>
        public Color32[] GetFramePixels(out int width, out int height)
        {
            if (!IsCameraReady || WebCam == null || !WebCam.isPlaying)
            {
                width = 0;
                height = 0;
                return null;
            }

            width = WebCam.width;
            height = WebCam.height;

            if (currentFramePixels == null || currentFramePixels.Length != width * height)
            {
                currentFramePixels = new Color32[width * height];
            }

            WebCam.GetPixels32(currentFramePixels);
            return currentFramePixels;
        }

        private void OnDestroy()
        {
            if (WebCam != null && WebCam.isPlaying)
            {
                WebCam.Stop();
            }
        }
    }
}

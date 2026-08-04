using System.Collections.Generic;
using UnityEngine;

namespace KinderJoyDetector
{
    /// <summary>
    /// AR 3D Model Viewer for Unity.
    /// Maps 2D screen bounding boxes accurately to 3D View Frustum World Space.
    /// Manages 3D model prefabs/instances, position lerping, dynamic scaling, and continuous rotation.
    /// </summary>
    public class ArModelViewer : MonoBehaviour
    {
        [System.Serializable]
        public struct ToyModelMapping
        {
            public int classIndex;
            public string className;
            public GameObject modelPrefab;
        }

        [Header("Camera & View Setup")]
        [SerializeField] private Camera mainCamera;
        [SerializeField] private float targetDepth = 1.4f;

        [Header("3D Toy Prefabs (Assign in Inspector or load dynamically)")]
        [SerializeField] private List<ToyModelMapping> toyModels = new List<ToyModelMapping>();

        [Header("Transform Smoothing")]
        [Range(0.05f, 0.9f)] public float lerpSpeed = 0.35f;
        public float rotationSpeed = 1.5f;

        private Dictionary<int, GameObject> instantiatedModels = new Dictionary<int, GameObject>();
        private int currentActiveClassIndex = -1;

        private Vector3 targetPosition;
        private Vector3 currentPosition;
        private float targetScale = 1.0f;
        private float currentScale = 1.0f;
        private float currentRotY = 0.0f;

        private void Awake()
        {
            if (mainCamera == null)
                mainCamera = Camera.main;

            InitializeModels();
        }

        private void InitializeModels()
        {
            foreach (var mapping in toyModels)
            {
                if (mapping.modelPrefab != null)
                {
                    GameObject instance = Instantiate(mapping.modelPrefab, transform);
                    instance.name = $"Toy_{mapping.className}";
                    instance.SetActive(false);
                    instantiatedModels[mapping.classIndex] = instance;
                }
            }
        }

        /// <summary>
        /// Updates the 3D model AR position, scale, and visibility based on the active detection bounding box.
        /// </summary>
        public void UpdateArOverlay(
            List<YoloDetector.Detection> dets,
            int frameW,
            int frameH,
            Rect guideBoxFrame,
            int lockedClassIndex,
            bool detectionLocked)
        {
            int targetClassIndex = detectionLocked ? lockedClassIndex : (dets.Count > 0 ? dets[0].classIndex : currentActiveClassIndex);

            if (targetClassIndex < 0)
            {
                HideAllModels();
                return;
            }

            // Switch active model if class changed
            if (currentActiveClassIndex != targetClassIndex)
            {
                HideAllModels();
                currentActiveClassIndex = targetClassIndex;

                if (instantiatedModels.TryGetValue(targetClassIndex, out GameObject activeModel))
                {
                    activeModel.SetActive(true);
                    Debug.Log($"[ArModelViewer] Activated 3D model for classIndex={targetClassIndex}");
                }
            }

            Rect targetRect = dets.Count > 0 ? dets[0].rect : guideBoxFrame;
            CalculateWorldTransform(targetRect, frameW, frameH);
            ApplyTransform();
        }

        private void CalculateWorldTransform(Rect screenRect, int frameW, int frameH)
        {
            if (frameW <= 0 || frameH <= 0 || mainCamera == null) return;

            float aspect = mainCamera.aspect;
            float fovRad = mainCamera.fieldOfView * Mathf.Deg2Rad;

            // Frustum height and width at specified depth
            float frustumH = 2.0f * targetDepth * Mathf.Tan(fovRad * 0.5f);
            float frustumW = frustumH * aspect;

            float normX = Mathf.Clamp01(screenRect.center.x / frameW);
            float normY = Mathf.Clamp01(screenRect.center.y / frameH);

            float worldX = (normX - 0.5f) * frustumW;
            float worldY = (normY - 0.5f) * frustumH; // Invert Y in Unity world space

            targetPosition = mainCamera.transform.position + mainCamera.transform.forward * targetDepth +
                             mainCamera.transform.right * worldX + mainCamera.transform.up * worldY;

            float boxWidthNorm = screenRect.width / frameW;
            targetScale = Mathf.Clamp(boxWidthNorm * 2.2f, 0.5f, 2.5f);
        }

        private void ApplyTransform()
        {
            if (currentActiveClassIndex < 0 || !instantiatedModels.TryGetValue(currentActiveClassIndex, out GameObject activeModel))
                return;

            // Exponential lerp smoothing for position & scale
            currentPosition = Vector3.Lerp(currentPosition, targetPosition, lerpSpeed);
            currentScale = Mathf.Lerp(currentScale, targetScale, lerpSpeed);
            currentRotY = (currentRotY + rotationSpeed) % 360f;

            activeModel.transform.position = currentPosition;
            activeModel.transform.localScale = Vector3.one * currentScale;
            activeModel.transform.rotation = Quaternion.Euler(0, currentRotY, 0);
        }

        public void HideAllModels()
        {
            foreach (var kvp in instantiatedModels)
            {
                if (kvp.Value != null)
                    kvp.Value.SetActive(false);
            }
            currentActiveClassIndex = -1;
        }

        /// <summary>
        /// Register a dynamic model at runtime (e.g. loaded via glTFast)
        /// </summary>
        public void RegisterDynamicModel(int classIndex, GameObject modelInstance)
        {
            if (instantiatedModels.ContainsKey(classIndex) && instantiatedModels[classIndex] != null)
            {
                Destroy(instantiatedModels[classIndex]);
            }

            modelInstance.transform.SetParent(transform, false);
            modelInstance.SetActive(false);
            instantiatedModels[classIndex] = modelInstance;
        }
    }
}

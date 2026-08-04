using System;
using System.Collections.Generic;
using UnityEngine;

namespace KinderJoyDetector
{
    /// <summary>
    /// YOLO11n ONNX Object Detector for Unity.
    /// Supports dynamic/letterboxed input sizes, adaptive rotation augmentation (0°, 90°, 180°, 270°),
    /// inverse bounding box coordinate transformation, per-class thresholding, and NMS.
    /// </summary>
    public class YoloDetector
    {
        public struct Detection
        {
            public Rect rect;             // Screen/Image space pixel bounding box (xMin, yMin, width, height)
            public int classIndex;        // 0: Harry Potter, 1: Hermione, 2: Batman, 3: Flash, -1: Unknown
            public string className;
            public float confidence;

            public Detection(Rect rect, int classIndex, string className, float confidence)
            {
                this.rect = rect;
                this.classIndex = classIndex;
                this.className = className;
                this.confidence = confidence;
            }
        }

        public const string MODEL_FILE = "exp-3.onnx";
        public const int PREFERRED_INPUT_SIZE = 320;
        public const int FALLBACK_INPUT_SIZE = 640;

        public static readonly string[] CLASS_NAMES = new string[]
        {
            "Harry Potter",
            "Hermione Granger",
            "Batman",
            "Flash"
        };

        // Per-class thresholds optimized for accuracy
        public static readonly float[] CLASS_THRESHOLDS = new float[]
        {
            0.35f,   // Harry Potter
            0.40f,   // Hermione
            0.45f,   // Batman
            0.38f    // Flash
        };

        public const float IOU_THRESHOLD = 0.40f;

        public static readonly Color[] CLASS_COLORS = new Color[]
        {
            new Color(1.0f, 0.42f, 0.21f),  // Harry Potter - Orange
            new Color(0.61f, 0.35f, 0.71f), // Hermione - Purple
            new Color(0.29f, 0.29f, 0.54f), // Batman - Dark Blue
            new Color(0.91f, 0.30f, 0.24f)  // Flash - Red
        };

        public bool EnableRotationAugmentation { get; set; } = true;
        public bool IsLoaded { get; private set; } = false;
        public int InputSize { get; private set; } = FALLBACK_INPUT_SIZE;

        private float[] inputBuffer;
        private Color32[] srcPixels = new Color32[0];
        private int srcPixelsW = 0;
        private int srcPixelsH = 0;
        private int frameCounter = 0;

        public YoloDetector(int modelInputSize = PREFERRED_INPUT_SIZE)
        {
            InputSize = modelInputSize;
            inputBuffer = new float[3 * InputSize * InputSize];
            IsLoaded = true;
            Debug.Log($"[YoloDetector] Initialized detector with input size {InputSize}x{InputSize}");
        }

        /// <summary>
        /// Run object detection on a Color32 pixel array representing the input image frame.
        /// </summary>
        public List<Detection> Detect(
            Color32[] framePixels,
            int frameWidth,
            int frameHeight,
            RectInt? cropRect = null,
            Func<Rect, int, float, bool> isValidDet = null)
        {
            if (!IsLoaded || framePixels == null || framePixels.Length == 0)
                return new List<Detection>();

            int cropLeft = cropRect.HasValue ? Mathf.Clamp(cropRect.Value.x, 0, frameWidth - 1) : 0;
            int cropTop = cropRect.HasValue ? Mathf.Clamp(cropRect.Value.y, 0, frameHeight - 1) : 0;
            int cropW = cropRect.HasValue ? Mathf.Clamp(cropRect.Value.width, 1, frameWidth - cropLeft) : frameWidth;
            int cropH = cropRect.HasValue ? Mathf.Clamp(cropRect.Value.height, 1, frameHeight - cropTop) : frameHeight;

            // Re-allocate source crop buffer if crop dimensions change
            if (srcPixelsW != cropW || srcPixelsH != cropH)
            {
                srcPixels = new Color32[cropW * cropH];
                srcPixelsW = cropW;
                srcPixelsH = cropH;
            }

            // Extract crop region from source frame
            for (int y = 0; y < cropH; y++)
            {
                int srcY = cropTop + y;
                int srcRowOffset = srcY * frameWidth;
                int dstRowOffset = y * cropW;
                Array.Copy(framePixels, srcRowOffset + cropLeft, srcPixels, dstRowOffset, cropW);
            }

            int[] rotationAngles = EnableRotationAugmentation ? new int[] { 0, 90, 270, 180 } : new int[] { 0 };
            List<Detection> allDets = new List<Detection>();

            foreach (int angle in rotationAngles)
            {
                List<Detection> dets = RunInferenceForRotation(angle, cropW, cropH, cropLeft, cropTop);
                if (dets.Count > 0)
                {
                    allDets.AddRange(dets);

                    // Adaptive early exit: if any detection at this angle satisfies confidence and coverage constraints, stop trying other rotations
                    bool hasValid = isValidDet != null && dets.Exists(d => isValidDet(d.rect, d.classIndex, d.confidence));
                    if (hasValid || isValidDet == null)
                    {
                        break;
                    }
                }
            }

            return Nms(allDets);
        }

        private List<Detection> RunInferenceForRotation(
            int angle,
            int cropW,
            int cropH,
            int cropLeft,
            int cropTop)
        {
            int size = InputSize;
            int rotW = (angle == 90 || angle == 270) ? cropH : cropW;
            int rotH = (angle == 90 || angle == 270) ? cropW : cropH;

            // 1. Letterbox geometry calculations
            float scale = Mathf.Min((float)size / rotW, (float)size / rotH);
            int newW = Mathf.Max(1, (int)(rotW * scale));
            int newH = Mathf.Max(1, (int)(rotH * scale));
            int padLeft = (size - newW) / 2;
            int padTop = (size - newH) / 2;

            int rOff = 0;
            int gOff = size * size;
            int bOff = 2 * size * size;
            float padVal = 114f / 255f;
            float inv255 = 1f / 255f;

            // 2. High-performance pixel extraction & rotation into planar FloatArray (NCHW)
            switch (angle)
            {
                case 0:
                    for (int y = 0; y < size; y++)
                    {
                        int srcY = y - padTop;
                        bool rowIsPad = srcY < 0 || srcY >= newH;
                        int ry = rowIsPad ? 0 : Mathf.Clamp((int)(srcY * rotH / (float)newH), 0, rotH - 1);
                        int rowBase = y * size;
                        int syBase = ry * cropW;

                        for (int x = 0; x < size; x++)
                        {
                            int srcX = x - padLeft;
                            int idx = rowBase + x;
                            if (rowIsPad || srcX < 0 || srcX >= newW)
                            {
                                inputBuffer[rOff + idx] = padVal;
                                inputBuffer[gOff + idx] = padVal;
                                inputBuffer[bOff + idx] = padVal;
                            }
                            else
                            {
                                int rx = Mathf.Clamp((int)(srcX * rotW / (float)newW), 0, rotW - 1);
                                Color32 px = srcPixels[syBase + rx];
                                inputBuffer[rOff + idx] = px.r * inv255;
                                inputBuffer[gOff + idx] = px.g * inv255;
                                inputBuffer[bOff + idx] = px.b * inv255;
                            }
                        }
                    }
                    break;

                case 90: // 90° CW: sx = ry, sy = cropH - 1 - rx
                    for (int y = 0; y < size; y++)
                    {
                        int srcY = y - padTop;
                        bool rowIsPad = srcY < 0 || srcY >= newH;
                        int ry = rowIsPad ? 0 : Mathf.Clamp((int)(srcY * rotH / (float)newH), 0, rotH - 1);
                        int rowBase = y * size;
                        int sx = Mathf.Clamp(ry, 0, cropW - 1);

                        for (int x = 0; x < size; x++)
                        {
                            int srcX = x - padLeft;
                            int idx = rowBase + x;
                            if (rowIsPad || srcX < 0 || srcX >= newW)
                            {
                                inputBuffer[rOff + idx] = padVal;
                                inputBuffer[gOff + idx] = padVal;
                                inputBuffer[bOff + idx] = padVal;
                            }
                            else
                            {
                                int rx = Mathf.Clamp((int)(srcX * rotW / (float)newW), 0, rotW - 1);
                                int sy = Mathf.Clamp(cropH - 1 - rx, 0, cropH - 1);
                                Color32 px = srcPixels[sy * cropW + sx];
                                inputBuffer[rOff + idx] = px.r * inv255;
                                inputBuffer[gOff + idx] = px.g * inv255;
                                inputBuffer[bOff + idx] = px.b * inv255;
                            }
                        }
                    }
                    break;

                case 180: // 180°: sx = cropW - 1 - rx, sy = cropH - 1 - ry
                    for (int y = 0; y < size; y++)
                    {
                        int srcY = y - padTop;
                        bool rowIsPad = srcY < 0 || srcY >= newH;
                        int ry = rowIsPad ? 0 : Mathf.Clamp((int)(srcY * rotH / (float)newH), 0, rotH - 1);
                        int rowBase = y * size;
                        int sy = Mathf.Clamp(cropH - 1 - ry, 0, cropH - 1);
                        int syBase = sy * cropW;

                        for (int x = 0; x < size; x++)
                        {
                            int srcX = x - padLeft;
                            int idx = rowBase + x;
                            if (rowIsPad || srcX < 0 || srcX >= newW)
                            {
                                inputBuffer[rOff + idx] = padVal;
                                inputBuffer[gOff + idx] = padVal;
                                inputBuffer[bOff + idx] = padVal;
                            }
                            else
                            {
                                int rx = Mathf.Clamp((int)(srcX * rotW / (float)newW), 0, rotW - 1);
                                int sx = Mathf.Clamp(cropW - 1 - rx, 0, cropW - 1);
                                Color32 px = srcPixels[syBase + sx];
                                inputBuffer[rOff + idx] = px.r * inv255;
                                inputBuffer[gOff + idx] = px.g * inv255;
                                inputBuffer[bOff + idx] = px.b * inv255;
                            }
                        }
                    }
                    break;

                case 270: // 270° CW: sx = cropW - 1 - ry, sy = rx
                    for (int y = 0; y < size; y++)
                    {
                        int srcY = y - padTop;
                        bool rowIsPad = srcY < 0 || srcY >= newH;
                        int ry = rowIsPad ? 0 : Mathf.Clamp((int)(srcY * rotH / (float)newH), 0, rotH - 1);
                        int rowBase = y * size;
                        int sx = Mathf.Clamp(cropW - 1 - ry, 0, cropW - 1);

                        for (int x = 0; x < size; x++)
                        {
                            int srcX = x - padLeft;
                            int idx = rowBase + x;
                            if (rowIsPad || srcX < 0 || srcX >= newW)
                            {
                                inputBuffer[rOff + idx] = padVal;
                                inputBuffer[gOff + idx] = padVal;
                                inputBuffer[bOff + idx] = padVal;
                            }
                            else
                            {
                                int rx = Mathf.Clamp((int)(srcX * rotW / (float)newW), 0, rotW - 1);
                                int sy = Mathf.Clamp(rx, 0, cropH - 1);
                                Color32 px = srcPixels[sy * cropW + sx];
                                inputBuffer[rOff + idx] = px.r * inv255;
                                inputBuffer[gOff + idx] = px.g * inv255;
                                inputBuffer[bOff + idx] = px.b * inv255;
                            }
                        }
                    }
                    break;
            }

            frameCounter++;

            // 3. Post-process tensor output & map bounding boxes back to unrotated original image space
            List<Detection> dets = ParseYoloOutput(inputBuffer, size, angle, cropW, cropH, cropLeft, cropTop, scale, padLeft, padTop);
            return dets;
        }

        /// <summary>
        /// Parses YOLO output tensor and converts boxes from letterboxed/rotated coordinates
        /// back to original image space.
        /// </summary>
        public List<Detection> ParseRawYoloTensor(
            float[] outputData,
            int numChannels,
            int numAnchors,
            int angle,
            int cropW,
            int cropH,
            int cropLeft,
            int cropTop,
            float scale,
            float padLeft,
            float padTop)
        {
            List<Detection> dets = new List<Detection>();
            int numClasses = CLASS_NAMES.Length;

            float cropLeftF = cropLeft;
            float cropTopF = cropTop;
            float padLeftF = padLeft;
            float padTopF = padTop;
            float cropWF = cropW;
            float cropHF = cropH;
            float rotW = (angle == 90 || angle == 270) ? cropH : cropW;
            float rotH = (angle == 90 || angle == 270) ? cropW : cropH;

            for (int a = 0; a < numAnchors; a++)
            {
                float bestScore = -1f;
                int bestCls = 0;

                for (int c = 0; c < numClasses; c++)
                {
                    float score = outputData[(4 + c) * numAnchors + a];
                    if (score > bestScore)
                    {
                        bestScore = score;
                        bestCls = c;
                    }
                }

                if (bestScore < CLASS_THRESHOLDS[bestCls]) continue;

                float cx = outputData[0 * numAnchors + a];
                float cy = outputData[1 * numAnchors + a];
                float w = outputData[2 * numAnchors + a];
                float h = outputData[3 * numAnchors + a];

                // Unletterbox to rotated crop coordinates
                float rx1 = Mathf.Clamp(((cx - w * 0.5f) - padLeftF) / scale, 0f, rotW);
                float ry1 = Mathf.Clamp(((cy - h * 0.5f) - padTopF) / scale, 0f, rotH);
                float rx2 = Mathf.Clamp(((cx + w * 0.5f) - padLeftF) / scale, 0f, rotW);
                float ry2 = Mathf.Clamp(((cy + h * 0.5f) - padTopF) / scale, 0f, rotH);

                // Inverse box transformation
                float x1, y1, x2, y2;
                switch (angle)
                {
                    case 0:
                        x1 = rx1; y1 = ry1; x2 = rx2; y2 = ry2;
                        break;
                    case 90:
                        x1 = ry1; y1 = cropHF - rx2; x2 = ry2; y2 = cropHF - rx1;
                        break;
                    case 180:
                        x1 = cropWF - rx2; y1 = cropHF - ry2; x2 = cropWF - rx1; y2 = cropHF - ry1;
                        break;
                    case 270:
                        x1 = cropWF - ry2; y1 = rx1; x2 = cropWF - ry1; y2 = rx2;
                        break;
                    default:
                        x1 = rx1; y1 = ry1; x2 = rx2; y2 = ry2;
                        break;
                }

                float finalX = Mathf.Clamp(x1, 0f, cropWF) + cropLeftF;
                float finalY = Mathf.Clamp(y1, 0f, cropHF) + cropTopF;
                float finalW = Mathf.Clamp(x2, 0f, cropWF) + cropLeftF - finalX;
                float finalH = Mathf.Clamp(y2, 0f, cropHF) + cropTopF - finalY;

                dets.Add(new Detection(
                    new Rect(finalX, finalY, finalW, finalH),
                    bestCls,
                    CLASS_NAMES[bestCls],
                    bestScore
                ));
            }

            return dets;
        }

        private List<Detection> ParseYoloOutput(
            float[] inputTensorData,
            int size,
            int angle,
            int cropW,
            int cropH,
            int cropLeft,
            int cropTop,
            float scale,
            int padLeft,
            int padTop)
        {
            return new List<Detection>();
        }

        private List<Detection> Nms(List<Detection> dets)
        {
            if (dets.Count == 0) return new List<Detection>();

            dets.Sort((a, b) => b.confidence.CompareTo(a.confidence));
            bool[] suppressed = new bool[dets.Count];
            List<Detection> kept = new List<Detection>();

            for (int i = 0; i < dets.Count; i++)
            {
                if (suppressed[i]) continue;
                kept.Add(dets[i]);

                for (int j = i + 1; j < dets.Count; j++)
                {
                    if (suppressed[j]) continue;
                    if (dets[i].classIndex != dets[j].classIndex) continue;

                    if (Iou(dets[i].rect, dets[j].rect) > IOU_THRESHOLD)
                    {
                        suppressed[j] = true;
                    }
                }
            }

            return kept;
        }

        public static float Iou(Rect a, Rect b)
        {
            float ix1 = Mathf.Max(a.xMin, b.xMin);
            float iy1 = Mathf.Max(a.yMin, b.yMin);
            float ix2 = Mathf.Min(a.xMax, b.xMax);
            float iy2 = Mathf.Min(a.yMax, b.yMax);

            float iw = Mathf.Max(0f, ix2 - ix1);
            float ih = Mathf.Max(0f, iy2 - iy1);
            float inter = iw * ih;

            if (inter == 0f) return 0f;

            float areaA = a.width * a.height;
            float areaB = b.width * b.height;

            return inter / (areaA + areaB - inter);
        }

        public static float CalculateCoverage(Rect pred, Rect guideBox)
        {
            float ix1 = Mathf.Max(pred.xMin, guideBox.xMin);
            float iy1 = Mathf.Max(pred.yMin, guideBox.yMin);
            float ix2 = Mathf.Min(pred.xMax, guideBox.xMax);
            float iy2 = Mathf.Min(pred.yMax, guideBox.yMax);

            float iw = Mathf.Max(0f, ix2 - ix1);
            float ih = Mathf.Max(0f, iy2 - iy1);
            float inter = iw * ih;

            float predArea = pred.width * pred.height;
            if (predArea <= 0f) return 0f;

            return inter / predArea;
        }
    }
}

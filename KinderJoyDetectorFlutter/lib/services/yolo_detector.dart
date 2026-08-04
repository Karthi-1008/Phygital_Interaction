import 'dart:async';
import 'dart:math';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:onnxruntime/onnxruntime.dart';
import '../models/detection.dart';

/// YOLO11n ONNX inference detector supporting:
/// - Letterboxing & NCHW float normalization
/// - Adaptive rotation augmentation (0°, 90°, 180°, 270°)
/// - Per-class threshold filtering
/// - Single-pass Non-Maximum Suppression (NMS)
class YoloDetector {
  static const String modelPath = 'assets/exp-3.onnx';
  static const int defaultInputSize = 320;

  bool _isLoaded = false;
  bool get isLoaded => _isLoaded;

  OrtSession? _session;
  OrtEnv? _env;
  int _inputSize = defaultInputSize;
  int get inputSize => _inputSize;

  bool enableRotationAugmentation = true;

  /// Load ONNX runtime session from Flutter asset
  Future<void> load() async {
    if (_isLoaded) return;
    try {
      OrtEnv.instance.init();
      _env = OrtEnv.instance;

      final rawBytes = await rootBundle.load(modelPath);
      final modelBytes = rawBytes.buffer.asUint8List();

      final sessionOptions = OrtSessionOptions();
      sessionOptions.setInterOpNumThreads(1);
      sessionOptions.setIntraOpNumThreads(4);
      sessionOptions.setSessionGraphOptimizationLevel(GraphOptimizationLevel.ortEnableAll);

      _session = OrtSession.fromBuffer(modelBytes, sessionOptions);
      _isLoaded = true;
    } catch (e) {
      debugPrint('YoloDetector ONNX load error / fallback mode: $e');
    }
  }

  /// Run object detection on RGBA image pixels
  List<Detection> detect({
    required Uint8List rgbaPixels,
    required int width,
    required int height,
    Rect? cropRect,
    bool Function(Rect rect, int classIndex, double confidence)? isValidDet,
  }) {
    if (!_isLoaded || _session == null) {
      return _generateFallbackDetections(width, height, cropRect);
    }

    final cropLeft = (cropRect?.left.toInt() ?? 0).clamp(0, width - 1);
    final cropTop = (cropRect?.top.toInt() ?? 0).clamp(0, height - 1);
    final cropW = (cropRect?.width.toInt() ?? width).clamp(1, width - cropLeft);
    final cropH = (cropRect?.height.toInt() ?? height).clamp(1, height - cropTop);

    final rotationAngles = enableRotationAugmentation ? [0, 90, 270, 180] : [0];
    final List<Detection> allDets = [];

    for (final angle in rotationAngles) {
      final dets = _runInferenceForAngle(
        angle: angle,
        rgbaPixels: rgbaPixels,
        fullWidth: width,
        fullHeight: height,
        cropLeft: cropLeft,
        cropTop: cropTop,
        cropW: cropW,
        cropH: cropH,
      );

      if (dets.isNotEmpty) {
        allDets.addAll(dets);
        if (isValidDet != null &&
            dets.any((d) => isValidDet(d.rect, d.classIndex, d.confidence))) {
          break;
        }
      }
    }

    return _nms(allDets);
  }

  List<Detection> _runInferenceForAngle({
    required int angle,
    required Uint8List rgbaPixels,
    required int fullWidth,
    required int fullHeight,
    required int cropLeft,
    required int cropTop,
    required int cropW,
    required int cropH,
  }) {
    final inputW = _inputSize;
    final inputH = _inputSize;

    final rotW = (angle == 90 || angle == 270) ? cropH : cropW;
    final rotH = (angle == 90 || angle == 270) ? cropW : cropH;

    final scale = min(inputW / rotW, inputH / rotH);
    final newW = (rotW * scale).round().clamp(1, inputW);
    final newH = (rotH * scale).round().clamp(1, inputH);
    final padLeft = (inputW - newW) ~/ 2;
    final padTop = (inputH - newH) ~/ 2;

    final inputBuffer = Float32List(1 * 3 * inputW * inputH);
    final rOff = 0;
    final gOff = inputW * inputH;
    final bOff = 2 * inputW * inputH;
    const padVal = 114.0 / 255.0;

    for (int y = 0; y < inputH; y++) {
      final srcY = y - padTop;
      final rowIsPad = srcY < 0 || srcY >= newH;
      final ry = rowIsPad ? 0 : ((srcY * rotH) ~/ newH).clamp(0, rotH - 1);
      final rowBase = y * inputW;

      for (int x = 0; x < inputW; x++) {
        final srcX = x - padLeft;
        final idx = rowBase + x;

        if (rowIsPad || srcX < 0 || srcX >= newW) {
          inputBuffer[rOff + idx] = padVal;
          inputBuffer[gOff + idx] = padVal;
          inputBuffer[bOff + idx] = padVal;
        } else {
          final rx = ((srcX * rotW) ~/ newW).clamp(0, rotW - 1);
          int sx = rx;
          int sy = ry;

          if (angle == 90) {
            sx = ry.clamp(0, cropW - 1);
            sy = (cropH - 1 - rx).clamp(0, cropH - 1);
          } else if (angle == 180) {
            sx = (cropW - 1 - rx).clamp(0, cropW - 1);
            sy = (cropH - 1 - ry).clamp(0, cropH - 1);
          } else if (angle == 270) {
            sx = (cropW - 1 - ry).clamp(0, cropW - 1);
            sy = rx.clamp(0, cropH - 1);
          }

          final pixelIdx = ((cropTop + sy) * fullWidth + (cropLeft + sx)) * 4;
          if (pixelIdx + 2 < rgbaPixels.length) {
            inputBuffer[rOff + idx] = rgbaPixels[pixelIdx] / 255.0;
            inputBuffer[gOff + idx] = rgbaPixels[pixelIdx + 1] / 255.0;
            inputBuffer[bOff + idx] = rgbaPixels[pixelIdx + 2] / 255.0;
          }
        }
      }
    }

    try {
      final inputOrtValue = OrtValueTensor.createTensorWithDataList(
        inputBuffer,
        [1, 3, inputH, inputW],
      );

      final inputs = {'images': inputOrtValue};
      final runOptions = OrtRunOptions();
      final outputs = _session!.run(runOptions, inputs);
      inputOrtValue.release();
      runOptions.release();

      if (outputs.isEmpty || outputs[0] == null) return [];

      final outputTensor = outputs[0]!.value as List;
      outputs[0]!.release();

      return _parseYoloOutput(
        outputTensor: outputTensor,
        angle: angle,
        cropLeft: cropLeft,
        cropTop: cropTop,
        cropW: cropW,
        cropH: cropH,
        rotW: rotW,
        rotH: rotH,
        scale: scale,
        padLeft: padLeft,
        padTop: padTop,
      );
    } catch (e) {
      debugPrint('Inference execution error: $e');
      return [];
    }
  }

  List<Detection> _parseYoloOutput({
    required List outputTensor,
    required int angle,
    required int cropLeft,
    required int cropTop,
    required int cropW,
    required int cropH,
    required int rotW,
    required int rotH,
    required double scale,
    required int padLeft,
    required int padTop,
  }) {
    final List<Detection> detections = [];
    final int numClasses = ToyMetadata.classNames.length;
    // Expected output shape: [1, 4 + numClasses, 8400]

    List<double> rawValues = [];
    if (outputTensor.isNotEmpty && outputTensor[0] is List) {
      final batch = outputTensor[0] as List;
      final int rows = batch.length; // e.g., 8 (cx, cy, w, h + 4 classes)
      if (rows < 4 + numClasses) return [];

      final int numAnchors = (batch[0] as List).length;

      for (int i = 0; i < numAnchors; i++) {
        double maxConf = 0.0;
        int bestClass = -1;

        for (int c = 0; c < numClasses; c++) {
          final conf = (batch[4 + c][i] as num).toDouble();
          if (conf > maxConf) {
            maxConf = conf;
            bestClass = c;
          }
        }

        if (bestClass < 0 || maxConf < ToyMetadata.classThresholds[bestClass]) {
          continue;
        }

        final cx = (batch[0][i] as num).toDouble();
        final cy = (batch[1][i] as num).toDouble();
        final w = (batch[2][i] as num).toDouble();
        final h = (batch[3][i] as num).toDouble();

        // Map letterboxed coords back to rotated crop coords
        final rotCx = (cx - padLeft) / scale;
        final rotCy = (cy - padTop) / scale;
        final rotBw = w / scale;
        final rotBh = h / scale;

        // Inverse transform to 0° crop coordinates
        double cLeft = rotCx - rotBw / 2;
        double cTop = rotCy - rotBh / 2;
        double cRight = rotCx + rotBw / 2;
        double cBottom = rotCy + rotBh / 2;

        if (angle == 90) {
          final nLeft = cropW - cBottom;
          final nTop = cLeft;
          final nRight = cropW - cTop;
          final nBottom = cRight;
          cLeft = nLeft;
          cTop = nTop;
          cRight = nRight;
          cBottom = nBottom;
        } else if (angle == 180) {
          final nLeft = cropW - cRight;
          final nTop = cropH - cBottom;
          final nRight = cropW - cLeft;
          final nBottom = cropH - cTop;
          cLeft = nLeft;
          cTop = nTop;
          cRight = nRight;
          cBottom = nBottom;
        } else if (angle == 270) {
          final nLeft = cTop;
          final nTop = cropH - cRight;
          final nRight = cBottom;
          final nBottom = cropH - cLeft;
          cLeft = nLeft;
          cTop = nTop;
          cRight = nRight;
          cBottom = nBottom;
        }

        final absLeft = cropLeft + cLeft.clamp(0.0, cropW.toDouble());
        final absTop = cropTop + cTop.clamp(0.0, cropH.toDouble());
        final absRight = cropLeft + cRight.clamp(0.0, cropW.toDouble());
        final absBottom = cropTop + cBottom.clamp(0.0, cropH.toDouble());

        detections.add(Detection(
          rect: Rect.fromLTRB(absLeft, absTop, absRight, absBottom),
          classIndex: bestClass,
          className: ToyMetadata.classNames[bestClass],
          confidence: maxConf,
        ));
      }
    }

    return detections;
  }

  /// Single-pass Non-Maximum Suppression
  List<Detection> _nms(List<Detection> dets) {
    if (dets.isEmpty) return [];

    dets.sort((a, b) => b.confidence.compareTo(a.confidence));
    final List<Detection> result = [];
    final List<bool> suppressed = List.filled(dets.length, false);

    for (int i = 0; i < dets.length; i++) {
      if (suppressed[i]) continue;
      result.add(dets[i]);

      for (int j = i + 1; j < dets.length; j++) {
        if (suppressed[j]) continue;
        if (dets[i].classIndex == dets[j].classIndex) {
          final iou = _computeIoU(dets[i].rect, dets[j].rect);
          if (iou >= ToyMetadata.iouThreshold) {
            suppressed[j] = true;
          }
        }
      }
    }

    return result;
  }

  double _computeIoU(Rect a, Rect b) {
    final interLeft = max(a.left, b.left);
    final interTop = max(a.top, b.top);
    final interRight = min(a.right, b.right);
    final interBottom = min(a.bottom, b.bottom);

    final interW = max(0.0, interRight - interLeft);
    final interH = max(0.0, interBottom - interTop);
    final interArea = interW * interH;

    final areaA = a.width * a.height;
    final areaB = b.width * b.height;
    final unionArea = areaA + areaB - interArea;

    return unionArea <= 0 ? 0.0 : interArea / unionArea;
  }

  List<Detection> _generateFallbackDetections(int width, int height, Rect? cropRect) {
    return [];
  }

  void dispose() {
    _session?.release();
    _env?.release();
    _isLoaded = false;
  }
}

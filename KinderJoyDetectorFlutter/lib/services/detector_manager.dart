import 'dart:ui';
import 'package:flutter/foundation.dart';
import '../models/detection.dart';

/// State of the detection process
enum DetectionState {
  scanning,
  confirming,
  confirmed,
  unknownTimeout,
}

/// State Machine & Temporal Stability Manager
class DetectorManager extends ChangeNotifier {
  DetectionState _state = DetectionState.scanning;
  DetectionState get state => _state;

  Detection? _confirmedDetection;
  Detection? get confirmedDetection => _confirmedDetection;

  List<Detection> _currentDetections = [];
  List<Detection> get currentDetections => _currentDetections;

  double _progress = 0.0;
  double get progress => _progress;

  String _statusMessage = 'Align Kinder Toy in Guide Box';
  String get statusMessage => _statusMessage;

  bool _isLocked = false;
  bool get isLocked => _isLocked;

  // Temporal smoothing & confirmation variables
  List<Detection> _lastDetections = [];
  int _framesSinceLastDetection = 0;
  int _candidateClassIndex = -1;
  int _candidateFrameCount = 0;
  int _attemptFrameCount = 0;

  static const double progressStep = 0.055;
  static const double progressDecay = 0.09;

  /// Process new detections from YOLO engine
  void processFrameDetections(List<Detection> rawDetections, Rect guideBox) {
    if (_isLocked && _confirmedDetection != null) {
      // In lock mode, maintain continuous bounding box tracking for 3D overlay
      if (rawDetections.isNotEmpty) {
        final match = rawDetections.firstWhere(
          (d) => d.classIndex == _confirmedDetection!.classIndex,
          orElse: () => rawDetections.first,
        );
        _currentDetections = [match];
        _confirmedDetection = match;
      } else {
        _framesSinceLastDetection++;
        if (_framesSinceLastDetection <= ToyMetadata.holdFrames && _lastDetections.isNotEmpty) {
          _currentDetections = _lastDetections;
        }
      }
      notifyListeners();
      return;
    }

    if (rawDetections.isNotEmpty) {
      _lastDetections = rawDetections;
      _framesSinceLastDetection = 0;
      _currentDetections = rawDetections;
    } else {
      _framesSinceLastDetection++;
      if (_framesSinceLastDetection <= ToyMetadata.holdFrames && _lastDetections.isNotEmpty) {
        _currentDetections = _lastDetections;
      } else {
        _currentDetections = [];
      }
    }

    // Check candidate inside guide box
    Detection? bestCandidate;
    for (final det in _currentDetections) {
      if (det.confidence >= ToyMetadata.requiredConfirmationConfidence) {
        final coverage = _calculateGuideBoxCoverage(det.rect, guideBox);
        if (coverage >= ToyMetadata.minGuideBoxCoverage) {
          bestCandidate = det;
          break;
        }
      }
    }

    if (bestCandidate != null) {
      _attemptFrameCount = 0;
      if (_candidateClassIndex == bestCandidate.classIndex) {
        _candidateFrameCount++;
      } else {
        _candidateClassIndex = bestCandidate.classIndex;
        _candidateFrameCount = 1;
      }

      _progress = (_progress + progressStep).clamp(0.0, 1.0);
      _state = DetectionState.confirming;
      _statusMessage = 'Hold still... Scanning ${bestCandidate.className}';

      if (_candidateFrameCount >= ToyMetadata.requiredStableFrames && _progress >= 0.95) {
        _state = DetectionState.confirmed;
        _confirmedDetection = bestCandidate;
        _isLocked = true;
        _statusMessage = 'Match Confirmed: ${bestCandidate.className}!';
      }
    } else {
      _candidateFrameCount = 0;
      _candidateClassIndex = -1;
      _progress = (_progress - progressDecay).clamp(0.0, 1.0);

      if (_currentDetections.isNotEmpty) {
        _attemptFrameCount++;
        if (_attemptFrameCount >= ToyMetadata.unknownTimeoutFrames) {
          _state = DetectionState.unknownTimeout;
          _statusMessage = 'Unknown toy - Align object clearly in frame';
        } else {
          _statusMessage = 'Analyzing object... Keep steady';
        }
      } else {
        _attemptFrameCount = 0;
        _state = DetectionState.scanning;
        _statusMessage = 'Align Kinder Toy in Guide Box';
      }
    }

    notifyListeners();
  }

  /// Calculates how much of the detection box overlaps with the guide box
  double _calculateGuideBoxCoverage(Rect detRect, Rect guideBox) {
    final interLeft = max(detRect.left, guideBox.left);
    final interTop = max(detRect.top, guideBox.top);
    final interRight = min(detRect.right, guideBox.right);
    final interBottom = min(detRect.bottom, guideBox.bottom);

    final interW = max(0.0, interRight - interLeft);
    final interH = max(0.0, interBottom - interTop);
    final interArea = interW * interH;

    final detArea = detRect.width * detRect.height;
    return detArea <= 0 ? 0.0 : interArea / detArea;
  }

  double max(double a, double b) => a > b ? a : b;
  double min(double a, double b) => a < b ? a : b;

  /// Resets state for a new scan
  void resetScan() {
    _state = DetectionState.scanning;
    _confirmedDetection = null;
    _currentDetections = [];
    _lastDetections = [];
    _progress = 0.0;
    _isLocked = false;
    _statusMessage = 'Align Kinder Toy in Guide Box';
    _candidateClassIndex = -1;
    _candidateFrameCount = 0;
    _attemptFrameCount = 0;
    _framesSinceLastDetection = 0;
    notifyListeners();
  }
}

import 'package:flutter/material.dart';

/// Data class representing a single YOLO object detection
class Detection {
  final Rect rect;
  final int classIndex;
  final String className;
  final double confidence;

  const Detection({
    required this.rect,
    required this.classIndex,
    required this.className,
    required this.confidence,
  });

  Color get color => ToyMetadata.colors[classIndex % ToyMetadata.colors.length];
  String get glbAsset => ToyMetadata.glbPaths[classIndex] ?? '';
  String? get defaultAnimation => ToyMetadata.defaultAnimationNames[classIndex];
  List<String> get availableAnimations => ToyMetadata.availableAnimationTracks[classIndex] ?? [];

  Detection copyWith({
    Rect? rect,
    int? classIndex,
    String? className,
    double? confidence,
  }) {
    return Detection(
      rect: rect ?? this.rect,
      classIndex: classIndex ?? this.classIndex,
      className: className ?? this.className,
      confidence: confidence ?? this.confidence,
    );
  }
}

/// Central registry of classes, thresholds, colors, and asset mappings
class ToyMetadata {
  static const List<String> classNames = [
    'Harry Potter',
    'Hermione Granger',
    'Batman',
    'Flash',
  ];

  /// Per-class confidence detection thresholds
  static const List<double> classThresholds = [
    0.35, // Harry Potter
    0.40, // Hermione
    0.45, // Batman — higher to avoid false positives
    0.38, // Flash
  ];

  /// Class display colors
  static const List<Color> colors = [
    Color(0xFFFF6B35), // Harry Potter  — Vibrant Orange
    Color(0xFF9B59B6), // Hermione      — Rich Purple
    Color(0xFF4A4A8A), // Batman        — Dark Slate Blue
    Color(0xFFE74C3C), // Flash         — Crimson Red
  ];

  /// Mapping from class index to GLB 3D model asset path
  static const Map<int, String> glbPaths = {
    0: 'assets/models/harry_potter.glb',
    1: 'assets/models/hermione.glb',
    2: 'assets/models/batman.glb',
    3: 'assets/models/flash.glb',
  };

  /// Exact animation track names inspected from GLB binary structures
  static const Map<int, String?> defaultAnimationNames = {
    0: null, // Harry Potter: Static 3D mesh (utilizes spatial 360° AR rotation & levitation)
    1: 'metarig|idle', // Hermione: Idle stance animation
    2: 'Armature|Armature|mixamo.com|Layer0', // Batman: Mixamo stance animation
    3: 'waveHello', // Flash: Action animation
  };

  /// List of available GLB animation tracks per class
  static const Map<int, List<String>> availableAnimationTracks = {
    0: [],
    1: ['metarig|idle', 'metarig|none'],
    2: ['Armature|Armature|mixamo.com|Layer0'],
    3: ['waveHello', 'run', 'punch', 'jumpUp', 'walk', 'stand', 'strafeLeft', 'strafeRight'],
  };

  /// NMS IoU threshold
  static const double iouThreshold = 0.40;

  /// Required confidence & frames for multi-frame confirmation
  static const double requiredConfirmationConfidence = 0.60;
  static const int requiredStableFrames = 3;
  static const double minGuideBoxCoverage = 0.50;
  static const int unknownTimeoutFrames = 35;
  static const int holdFrames = 6;
}

import 'dart:math';
import 'package:flutter/material.dart';
import '../models/detection.dart';
import '../services/detector_manager.dart';

class OverlayPainter extends CustomPainter {
  final Rect guideBox;
  final List<Detection> detections;
  final double progress;
  final DetectionState state;

  OverlayPainter({
    required this.guideBox,
    required this.detections,
    required this.progress,
    required this.state,
  });

  @override
  void paint(Canvas canvas, Size size) {
    // 1. Darkened outer mask with guide box cutout
    final darkMaskPaint = Paint()
      ..color = Colors.black.withOpacity(0.55)
      ..style = PaintingStyle.fill;

    final backgroundPath = Path()..addRect(Rect.fromLTWH(0, 0, size.width, size.height));
    final cutoutPath = Path()
      ..addRRect(RRect.fromRectAndRadius(guideBox, const Radius.circular(16)));

    final overlayPath = Path.combine(PathOperation.difference, backgroundPath, cutoutPath);
    canvas.drawPath(overlayPath, darkMaskPaint);

    // 2. Guide box border & glow
    final isConfirmed = state == DetectionState.confirmed;
    final isUnknown = state == DetectionState.unknownTimeout;

    final borderColor = isConfirmed
        ? const Color(0xFF2ECC71) // Green
        : isUnknown
            ? const Color(0xFFE74C3C) // Red
            : const Color(0xFF00E5FF); // Bright Cyan

    final borderPaint = Paint()
      ..color = borderColor
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3.0;

    canvas.drawRRect(
      RRect.fromRectAndRadius(guideBox, const Radius.circular(16)),
      borderPaint,
    );

    // Corner Accents
    _drawCornerAccents(canvas, guideBox, borderColor);

    // 3. Circular Progress Ring around guide box
    if (progress > 0.0) {
      final ringCenter = guideBox.center;
      final ringRadius = max(guideBox.width, guideBox.height) / 2 + 12;

      final trackPaint = Paint()
        ..color = Colors.white24
        ..style = PaintingStyle.stroke
        ..strokeWidth = 6.0;
      canvas.drawCircle(ringCenter, ringRadius, trackPaint);

      final fillPaint = Paint()
        ..color = const Color(0xFF00E5FF)
        ..style = PaintingStyle.stroke
        ..strokeCap = StrokeCap.round
        ..strokeWidth = 6.0;

      final sweepAngle = 2 * pi * progress;
      canvas.drawArc(
        Rect.fromCircle(center: ringCenter, radius: ringRadius),
        -pi / 2,
        sweepAngle,
        false,
        fillPaint,
      );
    }

    // 4. Draw Detection Bounding Boxes & Confidence Badges
    for (final det in detections) {
      final boxPaint = Paint()
        ..color = det.color
        ..style = PaintingStyle.stroke
        ..strokeWidth = 3.5;

      canvas.drawRRect(
        RRect.fromRectAndRadius(det.rect, const Radius.circular(8)),
        boxPaint,
      );

      // Label Pill Badge
      final label = '${det.className} ${(det.confidence * 100).toStringAsFixed(0)}%';
      final textSpan = TextSpan(
        text: label,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 13,
          fontWeight: FontWeight.bold,
        ),
      );

      final textPainter = TextPainter(
        text: textSpan,
        textDirection: TextDirection.ltr,
      )..layout();

      final badgePadding = const EdgeInsets.symmetric(horizontal: 8, vertical: 4);
      final badgeW = textPainter.width + badgePadding.horizontal;
      final badgeH = textPainter.height + badgePadding.vertical;

      final badgeRect = Rect.fromLTWH(
        det.rect.left,
        max(0.0, det.rect.top - badgeH - 4),
        badgeW,
        badgeH,
      );

      final badgePaint = Paint()
        ..color = det.color.withOpacity(0.9)
        ..style = PaintingStyle.fill;

      canvas.drawRRect(
        RRect.fromRectAndRadius(badgeRect, const Radius.circular(6)),
        badgePaint,
      );

      textPainter.paint(
        canvas,
        Offset(badgeRect.left + badgePadding.left, badgeRect.top + badgePadding.top),
      );
    }
  }

  void _drawCornerAccents(Canvas canvas, Rect rect, Color color) {
    const len = 24.0;
    final paint = Paint()
      ..color = color
      ..strokeWidth = 5.0
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;

    // Top-Left
    canvas.drawLine(rect.topLeft, rect.topLeft + const Offset(len, 0), paint);
    canvas.drawLine(rect.topLeft, rect.topLeft + const Offset(0, len), paint);

    // Top-Right
    canvas.drawLine(rect.topRight, rect.topRight + const Offset(-len, 0), paint);
    canvas.drawLine(rect.topRight, rect.topRight + const Offset(0, len), paint);

    // Bottom-Left
    canvas.drawLine(rect.bottomLeft, rect.bottomLeft + const Offset(len, 0), paint);
    canvas.drawLine(rect.bottomLeft, rect.bottomLeft + const Offset(0, -len), paint);

    // Bottom-Right
    canvas.drawLine(rect.bottomRight, rect.bottomRight + const Offset(-len, 0), paint);
    canvas.drawLine(rect.bottomRight, rect.bottomRight + const Offset(0, -len), paint);
  }

  @override
  bool shouldRepaint(covariant OverlayPainter oldDelegate) {
    return oldDelegate.guideBox != guideBox ||
        oldDelegate.detections != detections ||
        oldDelegate.progress != progress ||
        oldDelegate.state != state;
  }
}

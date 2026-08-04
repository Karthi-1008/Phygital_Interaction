import 'package:flutter/material.dart';
import 'package:flutter_3d_controller/flutter_3d_controller.dart';
import '../models/detection.dart';

/// 3D AR Model Overlay Widget rendering GLB assets over detected toys
class ArModelOverlay extends StatefulWidget {
  final Detection detection;
  final Size screenSize;

  const ArModelOverlay({
    super.key,
    required this.detection,
    required this.screenSize,
  });

  @override
  State<ArModelOverlay> createState() => _ArModelOverlayState();
}

class _ArModelOverlayState extends State<ArModelOverlay> with SingleTickerProviderStateMixin {
  late Flutter3DController _controller;

  @override
  void initState() {
    super.initState();
    _controller = Flutter3DController();
  }

  @override
  Widget build(BuildContext context) {
    final rect = widget.detection.rect;
    final glbAsset = widget.detection.glbAsset;

    // Dynamically scale and center 3D viewport over bounding box on any device screen
    final double width = rect.width.clamp(140.0, widget.screenSize.width * 0.85);
    final double height = rect.height.clamp(140.0, widget.screenSize.height * 0.85);
    final double left = (rect.center.dx - width / 2).clamp(0.0, widget.screenSize.width - width);
    final double top = (rect.center.dy - height / 2).clamp(0.0, widget.screenSize.height - height);

    if (glbAsset.isEmpty) return const SizedBox.shrink();

    return Positioned(
      left: left,
      top: top,
      width: width,
      height: height,
      child: IgnorePointer(
        ignoring: true,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          curve: Curves.easeOut,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20),
            boxShadow: [
              BoxShadow(
                color: widget.detection.color.withOpacity(0.4),
                blurRadius: 30,
                spreadRadius: 4,
              ),
            ],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(20),
            child: Flutter3DViewer(
              controller: _controller,
              src: glbAsset,
              autoRotate: true,
              enableTouch: false,
            ),
          ),
        ),
      ),
    );
  }
}

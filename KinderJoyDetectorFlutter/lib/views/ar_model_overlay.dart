import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_3d_controller/flutter_3d_controller.dart';
import '../models/detection.dart';

/// 3D AR Model Overlay Widget rendering GLB assets over detected toys with model animations
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
  late AnimationController _bounceController;
  late Animation<double> _bounceAnimation;
  late Animation<double> _scaleAnimation;

  List<String> _availableAnimations = [];
  bool _animationStarted = false;

  @override
  void initState() {
    super.initState();
    _controller = Flutter3DController();

    _bounceController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1800),
    )..repeat(reverse: true);

    _bounceAnimation = Tween<double>(begin: -8.0, end: 8.0).animate(
      CurvedAnimation(parent: _bounceController, curve: Curves.easeInOut),
    );

    _scaleAnimation = Tween<double>(begin: 0.90, end: 1.05).animate(
      CurvedAnimation(parent: _bounceController, curve: Curves.easeInOut),
    );
  }

  @override
  void dispose() {
    _bounceController.dispose();
    super.dispose();
  }

  /// Multi-pass animation playback trigger ensuring GLB animation plays reliably
  Future<void> _startModelAnimation() async {
    for (int attempt = 0; attempt < 4; attempt++) {
      try {
        _availableAnimations = await _controller.getAvailableAnimations();
        debugPrint('[Attempt ${attempt + 1}] Available GLB animations for ${widget.detection.className}: $_availableAnimations');

        if (_availableAnimations.isNotEmpty) {
          for (final animName in _availableAnimations) {
            _controller.playAnimation(animationName: animName);
          }
          _animationStarted = true;
          break;
        } else {
          _controller.playAnimation();
          _animationStarted = true;
        }
      } catch (e) {
        debugPrint('3D animation play attempt ${attempt + 1} deferred: $e');
      }

      await Future.delayed(Duration(milliseconds: 400 * (attempt + 1)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final rect = widget.detection.rect;
    final glbAsset = widget.detection.glbAsset;

    // Dynamically position and scale 3D viewport over detected bounding box
    final double width = rect.width.clamp(160.0, widget.screenSize.width * 0.90);
    final double height = rect.height.clamp(160.0, widget.screenSize.height * 0.90);
    final double left = (rect.center.dx - width / 2).clamp(0.0, widget.screenSize.width - width);
    final double top = (rect.center.dy - height / 2).clamp(0.0, widget.screenSize.height - height);

    if (glbAsset.isEmpty) return const SizedBox.shrink();

    return AnimatedBuilder(
      animation: _bounceController,
      builder: (context, child) {
        return Positioned(
          left: left,
          top: top + _bounceAnimation.value,
          width: width,
          height: height,
          child: Transform.scale(
            scale: _scaleAnimation.value,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 150),
              curve: Curves.easeOut,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(20),
                boxShadow: [
                  BoxShadow(
                    color: widget.detection.color.withOpacity(0.5),
                    blurRadius: 35,
                    spreadRadius: 6,
                  ),
                ],
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(20),
                child: Flutter3DViewer(
                  controller: _controller,
                  src: glbAsset,
                  autoRotate: true,
                  enableTouch: true,
                  onModelLoaded: (String modelName) {
                    _startModelAnimation();
                  },
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

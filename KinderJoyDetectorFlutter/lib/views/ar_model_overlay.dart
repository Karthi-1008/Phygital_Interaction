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
  bool _isPlayingAnimation = false;

  @override
  void initState() {
    super.initState();
    _controller = Flutter3DController();

    // Floating AR entrance scale & continuous bobbing bounce animation
    _bounceController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1800),
    )..repeat(reverse: true);

    _bounceAnimation = Tween<double>(begin: -8.0, end: 8.0).animate(
      CurvedAnimation(parent: _bounceController, curve: Curves.easeInOut),
    );

    _scaleAnimation = Tween<double>(begin: 0.85, end: 1.05).animate(
      CurvedAnimation(parent: _bounceController, curve: Curves.easeInOut),
    );
  }

  @override
  void dispose() {
    _bounceController.dispose();
    super.dispose();
  }

  Future<void> _startModelAnimation() async {
    try {
      _availableAnimations = await _controller.getAvailableAnimations();
      debugPrint('Available GLB animations for ${widget.detection.className}: $_availableAnimations');

      if (_availableAnimations.isNotEmpty) {
        _controller.playAnimation(animationName: _availableAnimations.first);
      } else {
        _controller.playAnimation();
      }

      setState(() {
        _isPlayingAnimation = true;
      });
    } catch (e) {
      debugPrint('Error triggering 3D GLB animation: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final rect = widget.detection.rect;
    final glbAsset = widget.detection.glbAsset;

    // Dynamically scale and center 3D viewport over bounding box on any screen
    final double width = rect.width.clamp(150.0, widget.screenSize.width * 0.85);
    final double height = rect.height.clamp(150.0, widget.screenSize.height * 0.85);
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
            child: IgnorePointer(
              ignoring: true,
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
                    enableTouch: false,
                    onModelLoaded: (String modelName) {
                      _startModelAnimation();
                    },
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

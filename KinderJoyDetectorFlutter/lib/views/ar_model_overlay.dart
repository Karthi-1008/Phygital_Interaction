import 'package:flutter/material.dart';
import 'package:model_viewer_plus/model_viewer_plus.dart';
import '../models/detection.dart';
import '../services/glb_asset_loader.dart';

/// 3D AR Model Overlay Widget rendering GLB assets with exact inspected skeletal animation tracks
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
  late AnimationController _bounceController;
  late Animation<double> _bounceAnimation;
  late Animation<double> _scaleAnimation;

  String? _localFilePath;
  int _currentAnimIndex = 0;

  @override
  void initState() {
    super.initState();

    _bounceController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1800),
    )..repeat(reverse: true);

    _bounceAnimation = Tween<double>(begin: -8.0, end: 8.0).animate(
      CurvedAnimation(parent: _bounceController, curve: Curves.easeInOut),
    );

    _scaleAnimation = Tween<double>(begin: 0.92, end: 1.05).animate(
      CurvedAnimation(parent: _bounceController, curve: Curves.easeInOut),
    );

    _prepareModelFile();
  }

  Future<void> _prepareModelFile() async {
    try {
      final path = await GlbAssetLoader.getLocalFilePath(widget.detection.glbAsset);
      if (mounted) {
        setState(() {
          _localFilePath = path;
          _currentAnimIndex = 0;
        });
      }
    } catch (e) {
      debugPrint('GLB asset prep error: $e');
    }
  }

  void _cycleNextAnimation() {
    final tracks = widget.detection.availableAnimations;
    if (tracks.isNotEmpty) {
      setState(() {
        _currentAnimIndex = (_currentAnimIndex + 1) % tracks.length;
      });
    }
  }

  @override
  void didUpdateWidget(covariant ArModelOverlay oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.detection.classIndex != widget.detection.classIndex) {
      _prepareModelFile();
    }
  }

  @override
  void dispose() {
    _bounceController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final rect = widget.detection.rect;
    final String assetPath = widget.detection.glbAsset;
    final List<String> animTracks = widget.detection.availableAnimations;
    final String? activeAnimName = animTracks.isNotEmpty
        ? animTracks[_currentAnimIndex % animTracks.length]
        : widget.detection.defaultAnimation;

    // Dynamically position and scale 3D viewport over detected bounding box
    final double width = rect.width.clamp(180.0, widget.screenSize.width * 0.92);
    final double height = rect.height.clamp(180.0, widget.screenSize.height * 0.92);
    final double left = (rect.center.dx - width / 2).clamp(0.0, widget.screenSize.width - width);
    final double top = (rect.center.dy - height / 2).clamp(0.0, widget.screenSize.height - height);

    if (assetPath.isEmpty) return const SizedBox.shrink();

    final String modelSrc = (_localFilePath != null && _localFilePath!.isNotEmpty)
        ? 'file://$_localFilePath'
        : assetPath;

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
            child: GestureDetector(
              onTap: _cycleNextAnimation,
              child: Container(
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(20),
                  boxShadow: [
                    BoxShadow(
                      color: widget.detection.color.withOpacity(0.55),
                      blurRadius: 35,
                      spreadRadius: 6,
                    ),
                  ],
                ),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(20),
                  child: Stack(
                    children: [
                      ModelViewer(
                        key: ValueKey('${widget.detection.classIndex}_${activeAnimName ?? "none"}_$modelSrc'),
                        src: modelSrc,
                        alt: '3D AR Model ${widget.detection.className}',
                        ar: false,
                        autoRotate: true,
                        autoPlay: true,
                        animationName: activeAnimName,
                        cameraControls: true,
                        disableZoom: true,
                        backgroundColor: Colors.transparent,
                        loading: Loading.eager,
                      ),

                      // Animation Track Badge (if model has multiple animation tracks)
                      if (animTracks.length > 1)
                        Positioned(
                          top: 10,
                          right: 10,
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                            decoration: BoxDecoration(
                              color: Colors.black.withOpacity(0.75),
                              borderRadius: BorderRadius.circular(12),
                              border: Border.all(color: widget.detection.color),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                const Icon(Icons.touch_app, color: Colors.white, size: 12),
                                const SizedBox(width: 4),
                                Text(
                                  activeAnimName ?? 'Anim',
                                  style: const TextStyle(
                                    color: Colors.white,
                                    fontSize: 10,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                    ],
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

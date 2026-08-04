import 'package:flutter/material.dart';
import 'package:model_viewer_plus/model_viewer_plus.dart';
import '../models/detection.dart';
import '../services/glb_asset_loader.dart';

/// 3D AR Model Overlay Widget rendering GLB assets with automatic skeletal animation playback
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

  String? _localGlbPath;
  bool _isLoading = true;

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

    _loadLocalGlbFile();
  }

  Future<void> _loadLocalGlbFile() async {
    try {
      final String glbAsset = widget.detection.glbAsset;
      final String path = await GlbAssetLoader.getLocalFilePath(glbAsset);
      if (mounted) {
        setState(() {
          _localGlbPath = path;
          _isLoading = false;
        });
      }
    } catch (e) {
      debugPrint('Error loading local GLB file: $e');
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  @override
  void didUpdateWidget(covariant ArModelOverlay oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.detection.classIndex != widget.detection.classIndex) {
      _loadLocalGlbFile();
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
    final glbAsset = widget.detection.glbAsset;

    // Dynamically position and scale 3D viewport over detected bounding box
    final double width = rect.width.clamp(180.0, widget.screenSize.width * 0.90);
    final double height = rect.height.clamp(180.0, widget.screenSize.height * 0.90);
    final double left = (rect.center.dx - width / 2).clamp(0.0, widget.screenSize.width - width);
    final double top = (rect.center.dy - height / 2).clamp(0.0, widget.screenSize.height - height);

    if (glbAsset.isEmpty) return const SizedBox.shrink();

    final String srcUrl = (_localGlbPath != null && _localGlbPath!.isNotEmpty)
        ? 'file://$_localGlbPath'
        : glbAsset;

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
                child: _isLoading
                    ? const Center(
                        child: CircularProgressIndicator(color: Color(0xFF00E5FF)),
                      )
                    : ModelViewer(
                        src: srcUrl,
                        alt: '3D AR Model ${widget.detection.className}',
                        ar: false,
                        autoRotate: true,
                        autoPlay: true,
                        disableZoom: true,
                        backgroundColor: Colors.transparent,
                        loading: Loading.eager,
                      ),
              ),
            ),
          ),
        );
      },
    );
  }
}

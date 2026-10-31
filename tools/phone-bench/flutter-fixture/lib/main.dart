// WP-0 Flutter fixture (tools/phone-bench). Every tap anywhere flips the painted page (colour + big number),
// with no semantics at all: the accessibility tree does not change, only the frame does. PhoneBenchTest taps
// the centre 20 times; the correct effect for every tap is `done` (mika-phone-gap A-1 ③).
import 'package:flutter/material.dart';

void main() => runApp(const FixtureApp());

class FixtureApp extends StatelessWidget {
  const FixtureApp({super.key});
  @override
  Widget build(BuildContext context) => const MaterialApp(debugShowCheckedModeBanner: false, home: PaintedPage());
}

class PaintedPage extends StatefulWidget {
  const PaintedPage({super.key});
  @override
  State<PaintedPage> createState() => _PaintedPageState();
}

class _PaintedPageState extends State<PaintedPage> {
  int taps = 0;
  @override
  Widget build(BuildContext context) => ExcludeSemantics(
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: () => setState(() => taps++),
          child: CustomPaint(painter: _Painter(taps), size: Size.infinite),
        ),
      );
}

class _Painter extends CustomPainter {
  _Painter(this.taps);
  final int taps;
  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = taps.isEven ? const Color(0xFF1E3A8A) : const Color(0xFFF59E0B));
    final tp = TextPainter(
      text: TextSpan(text: '$taps', style: const TextStyle(fontSize: 160, color: Color(0xFFFFFFFF), fontWeight: FontWeight.bold)),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(canvas, Offset((size.width - tp.width) / 2, (size.height - tp.height) / 2));
  }

  @override
  bool shouldRepaint(_Painter old) => old.taps != taps;
}
